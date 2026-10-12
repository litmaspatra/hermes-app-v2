package com.omarqaterge.hermesmobile;

import com.omarqaterge.hermesmobile.VoiceCommands.Command;
import com.omarqaterge.hermesmobile.VoiceCommands.Type;

/** Plain-Java test (no JUnit): run by tools/java-unit-tests.sh. */
public class VoiceCommandsTest {
    static int n = 0;

    static void is(String say, Type t, String name, String text, int a, int b) {
        n++;
        Command c = VoiceCommands.parse(say);
        if (c.type != t || !c.name.equals(name) || !c.text.equals(text) || c.a != a || c.b != b)
            fail("[" + say + "] expected " + t + "(" + name + "|" + text + "|" + a + "|" + b + ") got " + c);
    }

    static void type(String say, Type t) {
        n++;
        Command c = VoiceCommands.parse(say);
        if (c.type != t) fail("[" + say + "] expected " + t + " got " + c);
    }

    static void fail(String m) {
        System.err.println("FAIL: " + m);
        System.exit(1);
    }

    public static void main(String[] args) {
        is("call Rahul", Type.CALL, "Rahul", "", 0, 0);
        is("Call Rahul Sharma please", Type.CALL, "Rahul Sharma", "", 0, 0);
        is("Rahul ko call karo", Type.CALL, "Rahul", "", 0, 0);
        is("mummy ko call laga do", Type.CALL, "mummy", "", 0, 0);
        is("phone Dr. Mehta", Type.CALL, "Dr. Mehta", "", 0, 0);
        is("send a message to Rahul saying I will be late", Type.MESSAGE, "Rahul", "I will be late", 0, 0);
        is("send sms to Priya that the file is ready", Type.MESSAGE, "Priya", "the file is ready", 0, 0);
        is("Rahul ko message bhejo kal milte hain", Type.MESSAGE, "Rahul", "kal milte hain", 0, 0);
        type("message Rahul hello", Type.UNKNOWN);          // messages need the explicit phrase
        type("text Rahul hello", Type.UNKNOWN);
        type("send message", Type.UNKNOWN);
        type("play", Type.MUSIC_PLAY);
        type("resume music", Type.MUSIC_PLAY);
        type("gaana chalao", Type.MUSIC_PLAY);
        type("pause", Type.MUSIC_PAUSE);
        type("stop the music", Type.MUSIC_PAUSE);
        type("next song", Type.MUSIC_NEXT);
        type("skip", Type.MUSIC_NEXT);
        type("previous track", Type.MUSIC_PREV);
        is("play Tum Hi Ho", Type.MUSIC_QUERY, "Tum Hi Ho", "", 0, 0);
        is("play the song Kesariya", Type.MUSIC_QUERY, "Kesariya", "", 0, 0);
        is("set a timer for 5 minutes", Type.TIMER, "", "", 300, 0);
        is("timer 30 seconds", Type.TIMER, "", "", 30, 0);
        is("set a 2 hour timer", Type.TIMER, "", "", 7200, 0);
        is("set an alarm for 6:30 am", Type.ALARM, "", "", 6, 30);
        is("set alarm at 7 pm", Type.ALARM, "", "", 19, 0);
        is("set alarm for 12 am", Type.ALARM, "", "", 0, 0);
        type("set alarm for 25", Type.UNKNOWN);
        is("turn on the flashlight", Type.TORCH, "", "", 1, 0);
        is("torch off", Type.TORCH, "", "", 0, 0);
        is("volume up", Type.VOLUME, "", "", 1, 0);
        is("decrease the volume", Type.VOLUME, "", "", -1, 0);
        is("set volume to 40 percent", Type.VOLUME, "", "", 40, 1);
        type("what time is it", Type.TIME);
        is("navigate to Jaipur railway station", Type.NAVIGATE, "Jaipur railway station", "", 0, 0);
        is("take me to Amer Fort", Type.NAVIGATE, "Amer Fort", "", 0, 0);
        type("open the camera", Type.BLOCKED);
        type("take a photo", Type.BLOCKED);
        type("read my messages", Type.BLOCKED);
        type("read my notifications", Type.BLOCKED);
        type("open whatsapp", Type.BLOCKED);
        type("delete all my files", Type.UNKNOWN);
        type("run ls in the terminal", Type.UNKNOWN);
        type("", Type.UNKNOWN);
        type("hello", Type.UNKNOWN);
        if (!VoiceCommands.nameKey("  Rāhul  Śharma ").equals("rahul sharma")) fail("nameKey accents");
        if (!VoiceCommands.nameKey("Dr. Mehta").equals("dr mehta")) fail("nameKey punctuation");
        n += 2;
        System.out.println("VoiceCommandsTest: " + n + " cases OK");
    }
}
