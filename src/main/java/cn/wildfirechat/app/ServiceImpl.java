package cn.wildfirechat.app;


import cn.wildfirechat.app.pojo.ConfirmSessionRequest;
import cn.wildfirechat.app.pojo.CreateSessionRequest;
import cn.wildfirechat.app.pojo.LoginResponse;
import cn.wildfirechat.app.pojo.SessionOutput;
import cn.wildfirechat.sdk.ChatAdmin;
import cn.wildfirechat.sdk.model.IMResult;
import cn.wildfirechat.sdk.model.Token;
import cn.wildfirechat.sdk.model.User;
import cn.wildfirechat.sdk.model.UserId;
import com.github.qcloudsms.SmsSingleSender;
import com.github.qcloudsms.SmsSingleSenderResult;
import com.github.qcloudsms.httpclient.HTTPException;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static cn.wildfirechat.app.RestResult.RestCode.ERROR_SESSION_NOT_SCANED;
import static cn.wildfirechat.app.RestResult.RestCode.ERROR_SESSION_NOT_VERIFIED;

@org.springframework.stereotype.Service
public class ServiceImpl implements Service {
    static class Count {
        long count;
        long startTime;
        void reset() {
            count = 1;
            startTime = System.currentTimeMillis();
        }

        boolean increaseAndCheck() {
            long now = System.currentTimeMillis();
            if (now - startTime > 86400000) {
                reset();
                return true;
            }
            count++;
            if (count > 10) {
                return false;
            }
            return true;
        }
    }
    private static final Logger LOG = LoggerFactory.getLogger(ServiceImpl.class);
    private static ConcurrentHashMap<String, Record> mRecords = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<String, PCSession> mPCSession = new ConcurrentHashMap<>();

    private static ConcurrentHashMap<String, Count> mCounts = new ConcurrentHashMap<>();

    @Autowired
    private SMSConfig mSMSConfig;

    @Autowired
    private IMConfig mIMConfig;

    @PostConstruct
    private void init() {
        ChatAdmin.init(mIMConfig.admin_url, mIMConfig.admin_secret);
    }

    @Override
    public RestResult sendCode(String mobile) {
        try {
            if (!Utils.isMobile(mobile)) {
                LOG.error("Not valid mobile {}", mobile);
                return RestResult.error(RestResult.RestCode.ERROR_INVALID_MOBILE);
            }

            Record record = mRecords.get(mobile);
            if (record != null && System.currentTimeMillis() - record.getTimestamp() < 60 * 1000) {
                LOG.error("Send code over frequency. timestamp {}, now {}", record.getTimestamp(), System.currentTimeMillis());
                return RestResult.error(RestResult.RestCode.ERROR_SEND_SMS_OVER_FREQUENCY);
            }
            Count count = mCounts.get(mobile);
            if (count == null) {
                count = new Count();
                mCounts.put(mobile, count);
            }

            if (!count.increaseAndCheck()) {
                LOG.error("Count check failure, already send {} messages today", count.count);
                return RestResult.error(RestResult.RestCode.ERROR_SEND_SMS_OVER_FREQUENCY);
            }

            String code = Utils.getRandomCode(4);
            String[] params = {code};
            SmsSingleSender ssender = new SmsSingleSender(mSMSConfig.appid, mSMSConfig.appkey);
            SmsSingleSenderResult result = ssender.sendWithParam("86", mobile,
                    mSMSConfig.templateId, params, null, "", "");
            if (result.result == 0) {
                mRecords.put(mobile, new Record(code, mobile));
                return RestResult.ok(null);
            } else {
                LOG.error("Failure to send SMS {}", result);
                return RestResult.error(RestResult.RestCode.ERROR_SERVER_ERROR);
            }
        } catch (HTTPException e) {
            // HTTP响应码错误
            e.printStackTrace();
        } catch (JSONException e) {
            // json解析错误
            e.printStackTrace();
        } catch (IOException e) {
            // 网络IO错误
            e.printStackTrace();
        }
        return RestResult.error(RestResult.RestCode.ERROR_SERVER_ERROR);
    }

    @Override
    public RestResult login(String mobile, String code, String clientId) {
        if (StringUtils.isEmpty(mSMSConfig.superCode) || !code.equals(mSMSConfig.superCode)) {
            Record record = mRecords.get(mobile);
            if (record == null || !record.getCode().equals(code)) {
                LOG.error("not empty or not correct");
                return RestResult.error(RestResult.RestCode.ERROR_CODE_INCORRECT);
            }
            if (System.currentTimeMillis() - record.getTimestamp() > 5 * 60 * 1000) {
                LOG.error("Code expired. timestamp {}, now {}", record.getTimestamp(), System.currentTimeMillis());
                return RestResult.error(RestResult.RestCode.ERROR_CODE_EXPIRED);
            }
        }

        try {
            //使用电话号码查询用户信息。
            IMResult<User> userResult = ChatAdmin.getUserByName(mobile);

            //如果用户信息不存在，创建用户
            User user;
            boolean isNewUser = false;
            if (userResult.getCode() == IMResult.IMResultCode.IMRESULT_CODE_NOT_EXIST.code) {
                LOG.info("User not exist, try to create");
                user = new User();
                user.setName(mobile);
                user.setDisplayName(mobile);
                user.setMobile(mobile);
                IMResult<UserId> userIdResult = ChatAdmin.createUser(user);
                if (userIdResult.getCode() == 0) {
                    user.setUserId(userIdResult.getResult().getUserId());
                    isNewUser = true;
                } else {
                    LOG.info("Create user failure {}", userIdResult.code);
                    return RestResult.error(RestResult.RestCode.ERROR_SERVER_ERROR);
                }
            } else if(userResult.getCode() != 0){
                LOG.error("Get user failure {}", userResult.code);
                return RestResult.error(RestResult.RestCode.ERROR_SERVER_ERROR);
            } else {
                user = userResult.getResult();
            }

            //使用用户id获取token
            IMResult<Token> tokenResult = ChatAdmin.getUserToken(user.getUserId(), clientId);
            if (tokenResult.getCode() != 0) {
                LOG.error("Get user failure {}", tokenResult.code);
                return RestResult.error(RestResult.RestCode.ERROR_SERVER_ERROR);
            }

            //返回用户id，token和是否新建
            LoginResponse response = new LoginResponse();
            response.setUserId(user.getUserId());
            response.setToken(tokenResult.getResult().getToken());
            response.setRegister(isNewUser);
            return RestResult.ok(response);
        } catch (Exception e) {
            e.printStackTrace();
            LOG.error("Exception happens {}", e);
            return RestResult.error(RestResult.RestCode.ERROR_SERVER_ERROR);
        }
    }


    @Override
    public RestResult createPcSession(CreateSessionRequest request) {
        PCSession session = new PCSession();
        session.setClientId(request.getClientId());
        session.setCreateDt(System.currentTimeMillis());
        session.setDuration(300*1000); //300 seconds

        if (StringUtils.isEmpty(request.getToken())) {
            request.setToken(UUID.randomUUID().toString());
        }

        session.setToken(request.getToken());
        mPCSession.put(request.getToken(), session);

        SessionOutput output = session.toOutput();

        return RestResult.ok(output);
    }

    @Override
    public RestResult loginWithSession(String token) {
        PCSession session = mPCSession.get(token);
        if (session != null) {
            if (session.getStatus() == 2) {
                //使用用户id获取token
                try {
                    IMResult<Token> tokenResult = ChatAdmin.getUserToken(session.getConfirmedUserId(), session.getClientId());
                    if (tokenResult.getCode() != 0) {
                        LOG.error("Get user failure {}", tokenResult.code);
                        return RestResult.error(RestResult.RestCode.ERROR_SERVER_ERROR);
                    }

                    //返回用户id，token和是否新建
                    LoginResponse response = new LoginResponse();
                    response.setUserId(session.getConfirmedUserId());
                    response.setToken(tokenResult.getResult().getToken());
                    return RestResult.ok(response);
                } catch (Exception e) {
                    e.printStackTrace();
                    return RestResult.error(RestResult.RestCode.ERROR_SERVER_ERROR);
                }
            } else {
                if (session.getStatus() == 0)
                    return RestResult.error(ERROR_SESSION_NOT_SCANED);
                else {
                    return RestResult.error(ERROR_SESSION_NOT_VERIFIED);
                }
            }
        } else {
            return RestResult.error(RestResult.RestCode.ERROR_SESSION_EXPIRED);
        }
    }

    @Override
    public RestResult scanPc(String token) {
        PCSession session = mPCSession.get(token);
        if (session != null) {
            SessionOutput output = session.toOutput();
            if (output.getExpired() > 0) {
                session.setStatus(1);
                output.setStatus(1);
                return RestResult.ok(output);
            } else {
                return RestResult.error(RestResult.RestCode.ERROR_SESSION_EXPIRED);
            }
        } else {
            return RestResult.error(RestResult.RestCode.ERROR_SESSION_EXPIRED);
        }
    }

    @Override
    public RestResult confirmPc(ConfirmSessionRequest request) {
        PCSession session = mPCSession.get(request.getToken());
        if (session != null) {
            SessionOutput output = session.toOutput();
            if (output.getExpired() > 0) {
                //todo 检查IMtoken，确认用户id不是冒充的
                session.setStatus(2);
                output.setStatus(2);
                session.setConfirmedUserId(request.getUser_id());
                return RestResult.ok(output);
            } else {
                return RestResult.error(RestResult.RestCode.ERROR_SESSION_EXPIRED);
            }
        } else {
            return RestResult.error(RestResult.RestCode.ERROR_SESSION_EXPIRED);
        }
    }
}
