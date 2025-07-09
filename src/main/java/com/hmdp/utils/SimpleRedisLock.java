package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";//用来区分分布式下不同JVM

    @Override
    public boolean tryLock(long timeOutSec) {
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId
                , timeOutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);//防止null空指针
    }

    @Override
    public void unlock() {
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        String value = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if (threadId.equals(value)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
