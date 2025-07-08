package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private RedisIdWorker redisIdWorker;
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


}
