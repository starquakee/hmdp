package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    @Override
    public Result follow(Long id, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        if(isFollow){
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            boolean isSuccess = save(follow);
            if(isSuccess) stringRedisTemplate.opsForSet().add("follow:"+userId, id.toString());
        }else{
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", id));
            if(isSuccess) stringRedisTemplate.opsForSet().remove("follow:"+userId, id.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long id) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("follow_user_id", id).count();
        return Result.ok(count>0);
    }

    @Override
    public Result common(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = "follow:" + userId;
        String key2 = "follow:" + id;
        Set<String> common = stringRedisTemplate.opsForSet().intersect(key, key2);
        if(common==null||common.isEmpty()) return Result.ok(Collections.emptyList());
        List<Long> ids = common.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOS = userService.listByIds(ids).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
