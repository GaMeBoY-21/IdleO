package com.idleo.controlplane.hosts.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class EnrolmentCodesTests {

    @Test
    void codesAreFourGroupsOfFiveCrockfordSymbols() {
        var random = new SecureRandom();
        for (int i = 0; i < 1000; i++) {
            assertThat(EnrolmentCodes.generate(random)).matches("[0-9A-HJKMNP-TV-Z]{5}(-[0-9A-HJKMNP-TV-Z]{5}){3}");
        }
    }

    @Test
    void decodingIgnoresCaseHyphensAndSpacesAndMapsLookAlikes() {
        assertThat(EnrolmentCodes.normalise("abcde-fghjk-mnpqr-stvwx")).contains("ABCDEFGHJKMNPQRSTVWX");
        assertThat(EnrolmentCodes.normalise(" OOOOO IIIII-LLLLL 22222 ")).contains("00000111111111122222");
    }

    @Test
    void rejectsAnythingThatCannotBeACode() {
        assertThat(EnrolmentCodes.normalise("UUUUU-UUUUU-UUUUU-UUUUU")).isEmpty();
        assertThat(EnrolmentCodes.normalise("ABCDE-FGHJK-MNPQR")).isEmpty();
        assertThat(EnrolmentCodes.normalise("ABCDE-FGHJK-MNPQR-STVWX-Y")).isEmpty();
        assertThat(EnrolmentCodes.normalise("ABCDE-FGHJK-MNPQR-STVW*")).isEmpty();
        assertThat(EnrolmentCodes.normalise("")).isEmpty();
    }

    @Test
    void theHashIsOfTheCanonicalCode() {
        assertThat(EnrolmentCodes.hash("ABCDEFGHJKMNPQRSTVWX"))
                .hasSize(32)
                .isEqualTo(EnrolmentCodes.hash(
                        EnrolmentCodes.normalise("abcde-fghjk-mnpqr-stvwx").orElseThrow()));
    }
}
