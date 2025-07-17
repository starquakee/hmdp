package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private ExecutorService executors = Executors.newFixedThreadPool(1000);

    @Test
    void testSave() {
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire("cache:shop:"+1L, shop, 10L, TimeUnit.SECONDS);
    }
    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable runnable = () -> {
            for(int i=0;i<100;i++){
                long id = redisIdWorker.nextId("order");
                System.out.println("id:"+id);
            }
            countDownLatch.countDown();
        };
        for(int j=0;j<300;j++){
            executors.submit(runnable);
        }
        countDownLatch.await();
    }
    @Test
    void loadShopData() {
        List<Shop> list = shopService.list();
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for(Map.Entry<Long, List<Shop>> entry : map.entrySet()){
            long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = value.stream()
                    .map(shop -> new RedisGeoCommands.GeoLocation<>(String.valueOf(shop.getId())
                            , new Point(shop.getX(), shop.getY()))).collect(Collectors.toList());
//            for(Shop shop : value){
//                stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), String.valueOf(shop.getId()));
//            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }
    @Test
    void testHyperLogLog() {
        String[] values = new String[1000];
        int j;
        for (int i = 0; i < 100000; i++) {
            j = i % 1000;
            values[j] = "user_" + j;
            if(j == 999){
                // 发送到Redis
                stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
            }
        }
        // 统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println("count = " + count);
    }
}
