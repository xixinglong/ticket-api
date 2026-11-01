package com.example.ticket;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {
    @Select("SELECT id, username, password_hash AS passwordHash, role, enabled FROM users WHERE username = #{username}")
    UserRow findByUsername(@Param("username") String username);
}
