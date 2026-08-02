package com.aitutor.vo;

import com.aitutor.entity.User;

public class UserVO {

    private Long id;
    private String username;
    private String nickname;
    private String role;

    public UserVO() {
    }

    public UserVO(Long id, String username, String nickname, String role) {
        this.id = id;
        this.username = username;
        this.nickname = nickname;
        this.role = role;
    }

    public static UserVO from(User user) {
        return new UserVO(user.getId(), user.getUsername(), user.getNickname(), user.getRole());
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
