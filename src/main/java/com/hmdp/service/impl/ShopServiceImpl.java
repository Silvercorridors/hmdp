package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import io.netty.util.internal.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

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

    // 缓存重建的线程池
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        // Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // Shop shop = queryWithPassThrough(id);
        // 互斥锁解决缓存击穿

        // Shop shop = queryWithMutex(id);
        // 逻辑过期解决缓存击穿
         Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Objects.isNull(shop) ? Result.fail("店铺不存在") : Result.ok(shop);
    }

    // 防止缓存击穿(互斥锁，缓存穿透时，多线程只能有一个线程访问数据库)
    // 防止缓存穿透
    public Shop queryWithMutex(Long id){
        // 1.redis缓存是否存在
        String key = CACHE_SHOP_KEY.concat(id.toString());
        String json = redisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json) && JSONUtil.isJson(json)){
            Shop shop = JSONUtil.toBean(json, Shop.class);
            return shop;
        }
        // 1.2 命中的是否是空值, 防止缓存穿透
        if (json != null){
            // 返回错误信息
            return null;
        }
        // 获取互斥锁，防止缓存击穿
        String lockKey = LOCK_SHOP_KEY.concat(id.toString());
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            if (Boolean.FALSE.equals(isLock)){
                Thread.sleep(50);
                queryWithMutex(id);
            }
            // 2 成功查数据库
            shop = getById(id);
            // 模拟重建延时
            Thread.sleep(200);
            // 3.店铺是否存在，存在添加缓存，不存在返回404
            if (Objects.isNull(shop)){
                // 3.1 将空值写入redis
                redisTemplate.opsForValue().set(key, StringUtil.EMPTY_STRING, CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 3.2 不存在，返回404
                return null;
            }
            // 3.2 添加缓存
            redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }

        return shop;
    }


    // 防止缓存穿透
    public Shop queryWithPassThrough(Long id){
        // 1.redis缓存是否存在
        String key = CACHE_SHOP_KEY.concat(id.toString());
        String json = redisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json) && JSONUtil.isJson(json)){
            Shop shop = JSONUtil.toBean(json, Shop.class);
            return shop;
        }
        // 1.2 命中的是否是空值, 防止缓存穿透
        if (json != null){
            // 返回错误信息
            return null;
        }
        // 2 成功查数据库
        Shop shop = getById(id);
        // 3.店铺是否存在，存在添加缓存，不存在返回404
        if (Objects.isNull(shop)){
            // 3.1 将空值写入redis
            redisTemplate.opsForValue().set(key, StringUtil.EMPTY_STRING, CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 3.2 不存在，返回404
            return null;
        }
        // 3.2 添加缓存
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    // 逻辑删除防止缓存击穿
    // 防止缓存穿透
    public Shop queryWithLogicExpire(Long id){
        // 1.redis缓存是否存在
        String key = CACHE_SHOP_KEY.concat(id.toString());
        String json = redisTemplate.opsForValue().get(key);
        // 如果为空, 直接返回
        if (StrUtil.isBlank(json)){
            try {
                saveShop2Redis(id, LOCK_SHOP_TTL);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            String jsonForBuild = redisTemplate.opsForValue().get(key);
            RedisData redisData = JSONUtil.toBean(jsonForBuild, RedisData.class);
            return BeanUtil.toBean(redisData, Shop.class);
        }
        // 不为空，判断数据是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        Shop shop = BeanUtil.toBean(redisData.getData(), Shop.class);
        // 未过期，直接返回
        if (LocalDateTime.now().isBefore(expireTime)){
            return shop;
        }
        // 过期了
        // 6.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY.concat(id.toString());
        boolean isLock = tryLock(lockKey);
        // 6.2 锁是否获取成功
        if (isLock){
        // TODO:6.3 成功，开启独立线程, 实现缓存重建

            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 拿到锁以后，要再次检测缓存是否过期
                    String jsonForDoubleCheck = redisTemplate.opsForValue().get(key);
                    LocalDateTime expireTimeCheck = JSONUtil.toBean(jsonForDoubleCheck, RedisData.class).getExpireTime();
                    if (LocalDateTime.now().isAfter(expireTimeCheck)){
                        // 重建缓存
                        saveShop2Redis(id, LOCK_SHOP_TTL);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.4 失败，返回过期的旧数据
        return shop;
    }

    private boolean tryLock(String key){
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        redisTemplate.delete(key);
    }

    private void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 1.查询店铺数据
        Shop shop = getById(id);
        // 模拟重建需要时间
        Thread.sleep(200);
        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3.放到redis缓存
        String key = CACHE_SHOP_KEY.concat(id.toString());
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        // 写入数据库
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空!");
        }
        updateById(shop);
        String key = CACHE_SHOP_KEY.concat(id.toString());
        // 删除缓存
        redisTemplate.delete(key);
        return Result.ok();
    }
}
