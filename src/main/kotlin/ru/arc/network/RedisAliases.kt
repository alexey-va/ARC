package ru.arc.network

/** Paper plugin aliases — implementation lives in arc-core-redis. */
typealias RedisManager = ru.arc.redis.RedisManager

typealias RedisOperations = ru.arc.redis.RedisOperations

typealias ChannelListener = ru.arc.redis.ChannelListener

typealias InMemoryRedis = ru.arc.redis.InMemoryRedis

/** @deprecated Use [InMemoryRedis] */
typealias TestRedisManager = ru.arc.redis.InMemoryRedis

typealias RedisConnection = ru.arc.redis.RedisConnection

typealias ServerIdentity = ru.arc.redis.ServerIdentity
