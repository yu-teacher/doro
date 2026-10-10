package com.hunnit_beasts.auth.core.totp;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/** 2FA 시크릿 컬럼의 투명 암호화. 엔티티에서는 평문으로 보이고 DB 에는 암호문(키가 있을 때)이 저장된다. */
public final class TotpSecretConverters {

    static final String ACTIVE_PURPOSE = "credentials.totp_secret";
    static final String PENDING_PURPOSE = "credentials.pending_totp_secret";

    private TotpSecretConverters() {
    }

    @Component
    @Converter
    public static class Active implements AttributeConverter<String, String> {
        private final TotpSecretCipher cipher;

        public Active(TotpSecretCipher cipher) {
            this.cipher = cipher;
        }

        @Override
        public String convertToDatabaseColumn(String attribute) {
            return cipher.encrypt(attribute, ACTIVE_PURPOSE);
        }

        @Override
        public String convertToEntityAttribute(String dbData) {
            return cipher.decrypt(dbData, ACTIVE_PURPOSE);
        }
    }

    @Component
    @Converter
    public static class Pending implements AttributeConverter<String, String> {
        private final TotpSecretCipher cipher;

        public Pending(TotpSecretCipher cipher) {
            this.cipher = cipher;
        }

        @Override
        public String convertToDatabaseColumn(String attribute) {
            return cipher.encrypt(attribute, PENDING_PURPOSE);
        }

        @Override
        public String convertToEntityAttribute(String dbData) {
            return cipher.decrypt(dbData, PENDING_PURPOSE);
        }
    }
}
