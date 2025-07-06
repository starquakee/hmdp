package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
//        有缓存击穿风险
//        Shop shop = queryWithPassThrough(id);
        //解决了缓存击穿
        Shop shop = queryWithMutex(id);
        if(shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id){
        String key = "cache:shop:" + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if(shopJson != null){// 缓存的""
            return null;
        }
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            if(!isLock){
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(shopJson)){
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            if(shopJson != null){// 缓存的""
                return null;
            }
            shop = getById(id);
            Thread.sleep(200);
            if(shop == null){
                stringRedisTemplate.opsForValue().set(key, "",2, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }
        return shop;
    }

    public Shop queryWithPassThrough(Long id){
        String key = "cache:shop:" + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if(shopJson != null){
            return null;
        }
        Shop shop = getById(id);
        if(shop == null){
            stringRedisTemplate.opsForValue().set(key, "",2, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop),30, TimeUnit.MINUTES);
        return shop;
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id=shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete("cache:shop:" + shop.getId());
        return Result.ok();
    }
}
