package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;
import java.util.Optional;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        // 1.查询 blog
        // 2.根据 blog 的 userId 查询用户信息
        return Optional.ofNullable(getById(id)).map(b -> {
            // 查询blog和blog作者信息
            queryBlogUser(b);
            // 查询blog是否被当前用户点赞
            isBlogLiked(b);
            return Result.ok(b);
        }).orElse(Result.fail("笔记不存在"));
    }

    private void isBlogLiked(Blog blog){
        Long userId = UserHolder.getUser().getId();
        String blogId = blog.getId().toString();
        String key = RedisConstants.CACHE_BLOG_IS_LIKED_KEY + blogId;
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        blog.setIsLike(Boolean.TRUE.equals(isMember));
    }

    @Override
    @Transactional
    public Result likeBlog(Long id) {
        // 获取登录用户，判断当前用户是否点赞
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.CACHE_BLOG_IS_LIKED_KEY + id;
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        // 如果未点赞，则可以点赞, 点赞数+1
        if (BooleanUtil.isFalse(isMember)){
            // 修改点赞数量
            boolean isAdd = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isAdd){
                // 保存用户到Redis的集合
                stringRedisTemplate.opsForSet().add(key, userId.toString());
            }
        } else {
            // 如果已点赞，则取消点赞，点赞数-1
            boolean isSub = update().setSql("liked = liked - 1").eq("id", id).update();
            // 把用户从Redis的set集合移除
            if (isSub){
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryMyBlog(Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = query().eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return null;
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
