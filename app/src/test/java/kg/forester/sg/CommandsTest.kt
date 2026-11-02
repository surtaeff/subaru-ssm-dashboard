package kg.forester.sg

import kg.forester.sg.service.Commands
import kg.forester.sg.service.CommandsEn
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandsTest {
    /** ~110 Russian phrases (navigation, music, volume, radio, values, wake word) parse to the expected command. */
    @Test fun grammar() = assertEquals(emptyList<String>(), Commands.selfTest())

    /** The English grammar's own sample phrases, wake word, own commands, and a hint for every intent, value, screen and place. */
    @Test fun grammarEnglish() = assertEquals(emptyList<String>(), CommandsEn.selfTest())
}
