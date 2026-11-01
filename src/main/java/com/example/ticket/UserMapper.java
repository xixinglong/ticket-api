package com.example.ticket;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface UserMapper {
    String COLUMNS = "id, username, password_hash AS passwordHash, role, enabled, auth_version AS authVersion";

    @Select("SELECT " + COLUMNS + " FROM users WHERE username = #{username}")
    UserRow findByUsername(@Param("username") String username);

    @Select("SELECT " + COLUMNS + " FROM users WHERE id = #{id}")
    UserRow findById(@Param("id") long id);

    @Select("SELECT id, username, role, enabled FROM users ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
    List<ManagedUserResponse> list(@Param("size") int size, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM users")
    long count();

    @Insert("INSERT INTO users(username,password_hash,role) VALUES (#{username},#{hash},#{role})")
    int insert(@Param("username") String username, @Param("hash") String hash, @Param("role") String role);

    @Update("UPDATE users SET enabled=#{enabled}, auth_version=auth_version+1 WHERE id=#{id} AND role IN ('USER','AGENT')")
    int setEnabled(@Param("id") long id, @Param("enabled") boolean enabled);

    @Update("UPDATE users SET password_hash=#{hash}, auth_version=auth_version+1 WHERE id=#{id} AND role IN ('USER','AGENT')")
    int resetPassword(@Param("id") long id, @Param("hash") String hash);
}
