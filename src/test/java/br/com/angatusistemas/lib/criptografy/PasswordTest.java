package br.com.angatusistemas.lib.criptografy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Hash de senha: o custo que o cliente não pode escolher, os prefixos de outros sistemas e o
 * hash malformado que não pode virar erro 500.
 *
 * @author Angatu Sistemas
 */
class PasswordTest {

    /** 53 caracteres válidos de salt+hash, para montar hashes de teste. */
    private static final String BODY = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0";

    @Test
    @DisplayName("hash e verificação de ida e volta")
    void hashAndCheckRoundTrip() {
        String hash = Password.hash("senha-forte-123");
        assertTrue(hash.startsWith("$2a$10$"));
        assertTrue(Password.checkCriptography("senha-forte-123", hash));
        assertFalse(Password.checkCriptography("outra", hash));
    }

    @Test
    @DisplayName("hash() sempre gera hash, mesmo de um valor com cara de hash")
    void hashAlwaysHashes() {
        String lookalike = "$2a$10$" + BODY;
        assertNotEquals(lookalike, Password.hash(lookalike));
    }

    @Test
    @DisplayName("criptography não guarda como veio um hash de custo alto")
    void criptographyRefusesExpensiveHashes() {
        String expensive = "$2a$16$" + BODY;
        String stored = Password.criptography(expensive);
        assertNotEquals(expensive, stored, "hash de custo 16 foi gravado como veio");
        assertTrue(stored.startsWith("$2a$10$"));

        String ordinary = "$2a$10$" + BODY;
        assertEquals(ordinary, Password.criptography(ordinary), "hash comum deve continuar sem re-hash");
    }

    @Test
    @DisplayName("verificar contra hash de custo alto responde false na hora, sem gastar CPU")
    void checkingAgainstAnExpensiveHashFailsFast() {
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertFalse(Password.checkCriptography("x", "$2a$16$" + BODY)));
    }

    @Test
    @DisplayName("hashes $2b$ e $2y$ de outros sistemas verificam a senha certa")
    void foreignPrefixesVerify() {
        String hash = Password.hash("minha-senha");
        String suffix = hash.substring(4);
        assertTrue(Password.checkCriptography("minha-senha", "$2y$" + suffix));
        assertTrue(Password.checkCriptography("minha-senha", "$2b$" + suffix));
        assertFalse(Password.checkCriptography("errada", "$2y$" + suffix));
    }

    @Test
    @DisplayName("hash malformado responde false, sem exceção")
    void malformedHashesReturnFalse() {
        for (String malformed : new String[] {"", "$2a$1", "$2a$10$curto", "texto", "$2a$99$" + BODY}) {
            assertFalse(Password.checkCriptography("x", malformed), malformed);
        }
        assertFalse(Password.checkCriptography(null, "$2a$10$" + BODY));
        assertFalse(Password.checkCriptography("x", null));
    }
}
