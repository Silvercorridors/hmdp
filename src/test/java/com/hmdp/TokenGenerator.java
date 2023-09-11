package com.hmdp;

import com.hmdp.dto.LoginFormDTO;
import com.hmdp.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

@SpringBootTest
public class TokenGenerator {

    @Autowired
    private UserServiceImpl userServiceImpl;

    @Test
    public void loginTest() throws IOException {
        Long generateNumber = 1000L;
        File file = new File("/test/tokens/token.txt");
        if (!file.exists()) {
            file.createNewFile();
        }
        BufferedWriter writer = new BufferedWriter(new FileWriter(file));
        for (int i = 0; i < generateNumber; i++) {
            String phoneNumber = PhoneNumberGenerator.generateRandomPhoneNumber();
            LoginFormDTO loginFormDTO = new LoginFormDTO();
            loginFormDTO.setPhone(phoneNumber);
            String token = (String) userServiceImpl.login(loginFormDTO).getData();
            writer.append(token);
            writer.newLine();
        }
        writer.flush();
        writer.close();
    }

}
