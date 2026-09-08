package com.xetax.crm.auth.user;

public interface AuthUserService {
    AuthUserDto createUser(AuthUserDto userDto);

    AuthUserDto getUserByEmail(String email);

    AuthUserDto updateUser(AuthUserDto userDto, String userId);

    void deleteUser(String userId);

    Iterable<AuthUserDto> getAllUser();

    Iterable<AuthUserDto> getUsersPage(int page, int size);

    AuthUserDto getUserById(String userId);
}
