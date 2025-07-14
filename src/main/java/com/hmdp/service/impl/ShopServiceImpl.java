package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
//        Shop shop = cacheClient.queryWithPassThrough("cache:shop:", id, Shop.class, this::getById
//                , 10L, TimeUnit.MINUTES);
        //解决缓存击穿
        Shop shop = queryWithMutex(id);
        //逻辑过期需要提前向redis里预热！！！！！
//        Shop shop = queryWithLogicalExpire(id);
//        Shop shop = cacheClient.queryWithLogicalExpire("cache:shop:", "lock:shop:"
//                , id, Shop.class, this::getById, 20L, TimeUnit.MINUTES);
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
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id){
        String key = "cache:shop:" + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        //缓存查询到了逻辑过期对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        JSONObject data = (JSONObject)redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        if(expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        //缓存已过期,需要缓存重载
        String lockKey = "lock:shop:" + id;
        boolean isLock = tryLock(lockKey);
        if(isLock){
            //double check
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isBlank(shopJson)){
                return null;
            }
            //缓存查询到了逻辑过期对象
            redisData = JSONUtil.toBean(shopJson, RedisData.class);
            expireTime = redisData.getExpireTime();
            data = (JSONObject)redisData.getData();
            shop = JSONUtil.toBean(data, Shop.class);
            if(expireTime.isAfter(LocalDateTime.now())){
                return shop;
            }
            //缓存已过期,需要缓存重载
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveShop2Redis(id, 2000L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unlock(lockKey);
                }
            });

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

    public void saveShop2Redis(Long id, Long expireSeconds) {
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        String key = "cache:shop:" + shop.getId();
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
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

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if(x==null||y==null){
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        int from = (current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current*SystemConstants.DEFAULT_PAGE_SIZE;
        String key = "shop:geo:" + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(key, GeoReference.fromCoordinate(x, y)
                , new Distance(5000), RedisGeoCommands.GeoSearchCommandArgs
                        .newGeoSearchArgs().includeDistance().limit(end));
        if(results==null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if(content.size()<=from){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(content.size());
        Map<String, Distance> distanceMap = new HashMap<>(content.size());
        content.stream().skip(from).forEach(result -> {
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            Distance distance = result.getDistance();
            distanceMap.put(shopId, distance);
        });
        query().in("id", ids).last("order by field(id," + StrUtil.join(",", ids) + ")").list();
        List<Shop> shops = query().in("id", ids).last("order by field(id," + StrUtil.join(",", ids) + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
