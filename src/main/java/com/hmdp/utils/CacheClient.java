package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @author xiaoyu
 * @date 2023/2/11
 * @apiNote
 */
@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit unit)   {
        //1、从Redis查询商铺缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2、判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //3、存在，直接返回
            return JSONUtil.toBean(json, type);

        }
        //判断命中的是否是空值
        if (json != null) {
            return null;
        }
        //4、不存在，根据id查询数据库
        R r = dbFallBack.apply(id);
        //5、不存在，返回错误
        if (r == null) {
            //将空值写入Redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }
        //6、存在，写入Redis
        this.set(key, r, time, unit);
        //7、返回
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    public <R, ID> R queryWithLogicExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit unit)   {
        //1、从Redis查询商铺缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2、判断是否存在
        if (StrUtil.isBlank(json)) {
            //3、不存在，直接返回
            return null;
        }
        //4、命中，需要先把JSON反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5、判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1、未过期，直接返回店铺信息
            return r;
        }
        //5.2、已过期，需要缓存重建
        //6、缓存重建
        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //6.2判断是否取锁成功
        if (isLock) {
            //再次判断是否过期
            if (expireTime.isAfter(LocalDateTime.now())) {
                //5.1、未过期，直接返回店铺信息
                return r;
            }
            //6.3成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    R r1 = dbFallBack.apply(id);
                    //写入Redis
                    this.setLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }

            });
        }

        //6.4返回过期的商铺信息
        return r;
    }

    /**
     * 获取锁
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);

        return BooleanUtil.isTrue(flag);
    }

    /**
     * 删除锁
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
