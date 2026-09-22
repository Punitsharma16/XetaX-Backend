package com.xetax.crm.auth.user;

import com.xetax.crm.auth.verify.EmailVerificationService;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Same routes the standalone auth service exposed — the UI's UserService and
 * AuthService call these paths verbatim, so merging auth into this application
 * required no frontend path change, only a base-URL change.
 */
@RestController
@RequestMapping("/auth/api/v1/users")
@AllArgsConstructor
@NoArgsConstructor
public class AuthUserController {

    @org.springframework.beans.factory.annotation.Autowired
    private EmailVerificationService verificationService;

    @Autowired
    AuthUserService userService;

    @PostMapping("/")
    public ResponseEntity<AuthUserDto> createUser(@RequestBody AuthUserDto userDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.createUser(userDto));
    }

    /**
     * Public self-service signup. isAdmin and parentId from the payload are
     * ignored so a caller cannot grant itself elevated access.
     */
    @PostMapping("/register")
    public ResponseEntity<AuthUserDto> register(@RequestBody AuthUserDto userDto) {
        userDto.setAdmin(false);
        userDto.setEnable(true);
        userDto.setParentId(null);

        // With a mail server in place nothing is written to the users table
        // yet: the sign-up waits for the code. An address typed wrongly, or
        // one belonging to somebody else, used to leave a real account behind
        // that nobody could use and that held the email and phone number.
        if (!verificationService.verificationRequired()) {
            userDto.setEmailVerified(true);
            return ResponseEntity.status(HttpStatus.CREATED).body(userService.createUser(userDto));
        }

        String email = verificationService.startSignup(userDto);

        // The panel reads emailVerified=false and sends the person to the
        // "enter the code" page; there is no account to describe yet.
        AuthUserDto pending = new AuthUserDto();
        pending.setEmail(email);
        pending.setName(userDto.getName());
        pending.setEmailVerified(false);
        pending.setEnable(true);
        return ResponseEntity.status(HttpStatus.CREATED).body(pending);
    }

    /**
     * Lists users. Without parameters it behaves exactly as before (full
     * list); pass {@code page} and {@code size} to get one page — the
     * payload-friendly path once the user base grows.
     */
    @GetMapping("/getAllUsers")
    public ResponseEntity<Iterable<AuthUserDto>> getAllUsers(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        if (page != null && size != null && size > 0) {
            return ResponseEntity.ok(userService.getUsersPage(Math.max(page, 0), Math.min(size, 200)));
        }
        return ResponseEntity.status(HttpStatus.OK).body(userService.getAllUser());
    }

    @GetMapping("/getUserByEmail/{email}")
    public ResponseEntity<AuthUserDto> getUserByEmail(@PathVariable String email) {
        return ResponseEntity.ok(userService.getUserByEmail(email));
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> deleteUser(@PathVariable String userId) {
        userService.deleteUser(userId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{userId}")
    public ResponseEntity<AuthUserDto> updateUser(
            @RequestBody AuthUserDto userDto,
            @PathVariable String userId) {
        return ResponseEntity.ok(userService.updateUser(userDto, userId));
    }

    @GetMapping("/getUserById/{userId}")
    public ResponseEntity<AuthUserDto> getUserById(@PathVariable("userId") String userId) {
        return ResponseEntity.ok(userService.getUserById(userId));
    }
}
