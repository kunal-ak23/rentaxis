package com.datagami.rentaxis.api.dto;

public record SetPasswordRequest(String token, String newPassword) {}
