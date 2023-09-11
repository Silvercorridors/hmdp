package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryList() {
        List<String> jsonList = stringRedisTemplate.opsForList().range(RedisConstants.CACHE_SHOPTYPE_KEY, 0, -1);
        List<ShopType> typeList;
        if (CollectionUtil.isNotEmpty(jsonList)){
            typeList = jsonList.stream()
                    .map(json -> JSONUtil.toBean(json, ShopType.class))
                    .sorted(Comparator.comparingInt(ShopType::getSort))
                    .collect(Collectors.toList());

            return Result.ok(typeList);
        }
        typeList = query().orderByAsc("sort").list();
        if (CollectionUtil.isEmpty(typeList)){
            return Result.fail("店铺类型为空");
        }
        List<String> jsons = typeList.stream().map(JSONUtil::toJsonStr).collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(RedisConstants.CACHE_SHOPTYPE_KEY, jsons);
        stringRedisTemplate.expire(RedisConstants.CACHE_SHOPTYPE_KEY, RedisConstants.CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        return Result.ok(typeList);
    }
}
