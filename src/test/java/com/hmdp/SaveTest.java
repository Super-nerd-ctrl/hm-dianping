package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * @author xiaoyu
 * @date 2023/2/11
 * @apiNote
 */

@SpringBootTest
public class SaveTest {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;
    @Test
    public void testSaveShop() throws InterruptedException {
        //shopService.saveShop2Redis(1L, 10L);
        Shop shop = shopService.getById(1L);
        cacheClient.setLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 2L, TimeUnit.SECONDS);
    }

}
