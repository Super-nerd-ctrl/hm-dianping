package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                //1、获取队列中的订单信息
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    //2、创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }


    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1、获取对象
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功
        if (!isLock) {
            //获取不成功
            log.error("不允许重复下单");
            return;
        }
        try {
            //获取代理对象（事务）
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }
    private IVoucherOrderService proxy;
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        //2、判断结果是否为0
        int i = result.intValue();
        if (i != 0) {
        // 不为0，代表没有购买资格
            return Result.fail(i == 1 ? "库存不足" : "不能重复下单");
        }

        //2.0有购买资格，把下单信息保存到阻塞队列
        //TODO 保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        //6.1、订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //6.2、用户id
        voucherOrder.setUserId(userId);
        //6.3、代金券id
        voucherOrder.setVoucherId(voucherId);
        //放入阻塞队列
        orderTasks.add(voucherOrder);
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5、一人一单
        Long userId = voucherOrder.getUserId();
        //5.1、查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //5.2、判断订单是否存在
        if (count > 0) {
            //用户已经购买过
            log.error("用户已经购买过一次了");
            return;
        }

        //6、扣减库存
        boolean success = seckillVoucherService.update()
                //set stock = stock - 1
                .setSql("stock = stock - 1")
                // where id = ? and stock = > 0
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if (!success) {
            //扣减失败
            log.error("库存不足");
            return;
        }
        //6、创建订单
        save(voucherOrder);
    }
}
