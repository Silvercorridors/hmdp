package com.hmdp;

import java.util.Random;
  
public class PhoneNumberGenerator {  
  
    private static final String PHONE_NUMBER_PREFIX = "13"; // 中国大陆手机号码的前两位通常是13，14，15，17，18，19等  
    private static final int PHONE_NUMBER_SUFFIX_LENGTH = 9;  
  
    private static final String PHONE_NUMBER_SUFFIX_CHARS = "0123456789";  
  
    private static Random random = new Random();
  
    public static String generateRandomPhoneNumber() {
        StringBuilder phoneNumber = new StringBuilder();  
        phoneNumber.append(PHONE_NUMBER_PREFIX);  
        for (int i = 0; i < PHONE_NUMBER_SUFFIX_LENGTH; i++) {  
            int randomIndex = random.nextInt(PHONE_NUMBER_SUFFIX_CHARS.length());  
            phoneNumber.append(PHONE_NUMBER_SUFFIX_CHARS.charAt(randomIndex));  
        }  
        return phoneNumber.toString();  
    }  
  
    public static void main(String[] args) {  
        String phoneNumber = generateRandomPhoneNumber();
        System.out.println("Generated phone number: " + phoneNumber);  
    }  
}