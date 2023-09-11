-- 比较线程标识与锁中的内容标识是否一致
if(redis.call('get', KEY[1]) == ARGV[1]) then
 -- 释放锁
 redis.call('del', KEY[1])
end
return 0