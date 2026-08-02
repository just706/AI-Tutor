package com.aitutor.vo;

public class LoginVO {

    private String token;
    private LoginUserVO user;

    public LoginVO() {
    }

    public LoginVO(String token, LoginUserVO user) {
        this.token = token;
        this.user = user;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public LoginUserVO getUser() {
        return user;
    }

    public void setUser(LoginUserVO user) {
        this.user = user;
    }
}
