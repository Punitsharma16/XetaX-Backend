package com.xetax.crm.auth.user;

public interface AuthUserService {
    AuthUserDto createUser(AuthUserDto userDto);

    /**
     * Creates an account whose email has just been confirmed, from a password
     * that was hashed when the person signed up. Used by the verification
     * step, which is the first moment a sign-up becomes a real user.
     */
    AuthUserDto createVerifiedUser(AuthUserDto userDto, String passwordHash);

    AuthUserDto getUserByEmail(String email);

    AuthUserDto updateUser(AuthUserDto userDto, String userId);

    void deleteUser(String userId);

    Iterable<AuthUserDto> getAllUser();

    Iterable<AuthUserDto> getUsersPage(int page, int size);

    AuthUserDto getUserById(String userId);
}
