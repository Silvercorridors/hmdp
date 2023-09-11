package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    public static final ExecutorService SECKILL_ORDER_SERVICE = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_SERVICE.submit(new VoucherOrderHandler());
    }
//    多线程调用时，使用AOP对象会报错
//    private static final IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();

    @Lazy
    @Resource
    private IVoucherOrderService voucherOrderService;

    private class VoucherOrderHandler implements Runnable{

        private static final String QUEUE_NAME = "stream.orders";

        @Override
        public void run() {
            while(true){
                try {
                    // 1.从消息队列中获取订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(QUEUE_NAME, ReadOffset.lastConsumed())

                    );
                    // 2.1 判断消息是否获取成功, 如果获取失败，说明没有消息，继续下一次循环
                    if (CollectionUtils.isEmpty(list)){
                        continue;
                    }
                    // 2.2 如果获取成功，可以下单
                    // 2.3 创建订单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                    // 2.4 ack 确认
                    stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME,"g1", record.getId());
                } catch (Exception e) {
                    log.error("订单处理异常", e);
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {
            while (true){
                try{
                    // 获取pendingList中的订单信息 XREADGROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(QUEUE_NAME, ReadOffset.from("0")));
                    // 2.1 判断消息是否获取成功, 如果获取失败，说明没有消息，继续下一次循环
                    if (CollectionUtils.isEmpty(list)){
                        break;
                    }
                    // 2.2 如果获取成功，可以下单
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    // 2.3 创建订单
                    handleVoucherOrder(voucherOrder);
                    // 2.4 ack 确认
                    stringRedisTemplate.opsForStream().acknowledge(QUEUE_NAME,"g1", record.getId());
                }catch (Exception e){
                    // 如果处理PendingList里的数据出现了异常，不用管，打个日志就可以。数据还会在PendingList里
                    log.error("处理pending-list异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        log.error("线程休眠异常", ex);
                    }
                }

            }

        }


    }

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.执行Lua脚本
        Long userId = UserHolder.getUser().getId();
        // 获取订单id
        long orderId = redisIdWorker.nextId("order");
        // 2.判断结果是否为 0
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId));
        // 2.1 不为0， 没有购买资格
        if (Objects.requireNonNull(result).intValue() != 0){
            return Result.fail(result.intValue() == 1 ? "库存不足" : "不能重复下单");
        }
        // 用另一个独立线程去消费消息队列里的信息
        // 3.返回订单id
        return Result.ok(orderId);
    }

/*    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券id
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始!");
        }
        // 3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束!");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足!
            return Result.fail("库存不足!");
        }

        // 创建redis锁对象
//        SimpleRedisLock lock
//                = new SimpleRedisLock(RedisConstants.LOCK_ORDER_KEY + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId);
        boolean isLock = lock.tryLock();
        // 判断是否获取锁成功
        if (!isLock){
            // 获取锁失败, 返回错误或重试
            return Result.fail("不允许重复下单");
        }
        try{
            // 获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId, userId);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }*/

//    @Transactional
//    @Deprecated
//    public Result createVoucherOrder(Long voucherId, Long userId) {
//        // 5.一人一单
//        // 5.1 查询订单, 一个人、一个优惠券id 只能对应一个不为已退款的订单
//        Integer count = query().eq("user_id", userId)
//                .eq("voucher_id", voucherId)
//                .ne("status", 6)
//                .count();
//        // 5.2 判断是否存在
//        if (count > 0) {
//            // 用户已经购买并且支付过了
//            return Result.fail("用户已经购买过一次");
//        }
//        // 6.扣减库存
//        boolean isSuccess = seckillVoucherService.update()
//                .setSql("stock = stock - 1") // set stock = stock - 1
//                .eq("voucher_id", voucherId)
//                .gt("stock", 0)
//                .update();
//        if (!isSuccess) {
//            return Result.fail("库存不足!");
//        }
//        // 6. 扣减成功，创建优惠券订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 6.1 订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        // 6.2 用户id
//        voucherOrder.setUserId(userId);
//        // 6.3 代金券id
//        voucherOrder.setVoucherId(voucherId);
//        voucherOrder.setStatus(1);
//        // 6.4 入库
//        save(voucherOrder);
//        // 7. 返回订单id
//        return Result.ok(orderId);
//    }

    private void handleVoucherOrder(VoucherOrder voucherOrder){
        // 1.获取队列中的订单信息
        Long userId = voucherOrder.getUserId();
        Long orderId = voucherOrder.getId();
        // 2.创建订单
        // 3.创建redis锁对象
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单, 用户Id: {}, orderId: {}", userId, orderId);
        }
        // 4.判断是否获取锁成功
        try {
            // 获取代理对象
//            proxy.createVoucherOrder(voucherOrder);
            voucherOrderService.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 6.1 获取优惠券id、订单id、用户id
        Long voucherId = voucherOrder.getVoucherId();
        Long userId = voucherOrder.getUserId();
        // 6.2 一人一单, 查询订单是否存在
        Integer count = query().eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();

        if (count > 0){
            log.error("此用户:{} 已经购买过一次了", userId);
        }
        // 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success){
            log.error("库存不足");
            return;
        }
        // 创建订单
        voucherOrder.setStatus(1);
        // 6.4 入库
        save(voucherOrder);
    }
}
