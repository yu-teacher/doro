package com.hunnit_beasts.auth.core.totp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 암호화 키가 설정돼 있으면 기동할 때 평문으로 저장된 2FA 시크릿을 암호문으로 바꾼다. 여러 번 실행해도 안전하다(이미 암호문인 값은 건드리지 않는다).
 * 엔티티를 거치지 않고 JDBC 로 바꾼다: 값이 바뀌지 않은 엔티티는 JPA 가 다시 쓰지 않아, 사용자가 2FA 를 다시 설정하기 전까지 평문이 남기 때문이다.
 */
@Slf4j
@Component
public class TotpSecretMigrator implements ApplicationRunner {

    private final TotpSecretCipher cipher;
    private final JdbcTemplate jdbc;

    public TotpSecretMigrator(TotpSecretCipher cipher, JdbcTemplate jdbc) {
        this.cipher = cipher;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrate();
    }

    /** @return 암호문으로 바꾼 행 수 */
    public int migrate() {
        if (!cipher.isEnabled()) {
            return 0;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, totp_secret, pending_totp_secret from credentials "
                        + "where (totp_secret is not null and totp_secret not like ?) or (pending_totp_secret is not null and pending_totp_secret not like ?)",
                TotpSecretCipher.PREFIX + "%", TotpSecretCipher.PREFIX + "%");
        for (Map<String, Object> row : rows) {
            jdbc.update("update credentials set totp_secret = ?, pending_totp_secret = ? where id = ?",
                    cipher.encrypt((String) row.get("totp_secret"), TotpSecretConverters.ACTIVE_PURPOSE),
                    cipher.encrypt((String) row.get("pending_totp_secret"), TotpSecretConverters.PENDING_PURPOSE),
                    row.get("id"));
        }
        if (!rows.isEmpty()) {
            log.info("Encrypted the plaintext 2FA secrets of {} credential row(s)", rows.size());
        }
        return rows.size();
    }
}
