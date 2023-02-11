package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

/**
 * @author xiaoyu
 * @date 2023/2/11
 * @apiNote
 */

@SpringBootTest
public class SaveTest {

    @Resource
    private ShopServiceImpl shopService;

    @Test
    public void testSaveShop() throws InterruptedException {
        shopService.saveShop2Redis(1L, 10L);
    }

}
