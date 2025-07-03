package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;


@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误!");
        }
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set("login:code:"+phone,code,2, TimeUnit.MINUTES);
//        session.setAttribute("code", code);
//        session.setAttribute("phone", phone);
        log.debug("发送短信成功，验证码为：{}", code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        if(RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            return Result.fail("手机号格式错误!");
        }
        String cacheCode = stringRedisTemplate.opsForValue().get("login:code:"+loginForm.getPhone());
        if(cacheCode == null || !cacheCode.equals(loginForm.getCode())){
            return Result.fail("验证码错误!");
        }
//        String phone = (String) session.getAttribute("phone");
//        if(phone == null || !phone.equals(loginForm.getPhone())){
//            return Result.fail("手机号错误!");
//        }
        User user = query().eq("phone", loginForm.getPhone()).one();
        if(user == null){
            user = createUserWithPhone(loginForm.getPhone());
        }
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll("login:token:"+token, userMap);
        stringRedisTemplate.expire("login:token:"+token, 300, TimeUnit.MINUTES);
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok(token);
    }

    @Override
    public Result logout() {
        return null;
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
