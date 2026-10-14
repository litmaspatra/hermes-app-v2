package com.omarqaterge.hermesmobile;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.telecom.TelecomManager;
import android.telephony.SmsManager;
import android.view.KeyEvent;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one parsed voice command from the fixed safe set (see VoiceCommands) and returns the sentence to speak.
 * Never opens private content, never runs Hermes agent actions, never tries to get around the lock screen:
 * when the phone is locked and Android won't start something, it says "Unlock to ...".
 */
final class VoiceActions {
    private VoiceActions() {}

    static boolean locked(Context c) {
        KeyguardManager km = c.getSystemService(KeyguardManager.class);
        return km != null && km.isKeyguardLocked();
    }

    private static boolean has(Context c, String perm) {
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    /** One contact with the numbers it has. */
    private static final class Person {
        String name;
        final List<String> numbers = new ArrayList<>();
        final List<Integer> types = new ArrayList<>();
    }

    /** Every contact, read once and kept for a minute (a call has to start at once; scanning a big book each time is slow). */
    private static List<Person> book;
    private static long bookAt;

    private static synchronized List<Person> people(Context c) {
        if (book != null && System.currentTimeMillis() - bookAt < 60_000) return book;
        Map<Long, Person> m = new LinkedHashMap<>();
        ContentResolver cr = c.getContentResolver();
        try (Cursor q = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[] {ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE}, null, null, null)) {
            int n = 0;
            while (q != null && q.moveToNext() && n++ < 30000) {
                String name = q.getString(1);
                if (name == null || name.trim().isEmpty()) continue;
                long id = q.getLong(0);
                Person p = m.get(id);
                if (p == null) {
                    p = new Person();
                    p.name = name;
                    m.put(id, p);
                }
                String num = q.getString(2);
                if (num == null) continue;
                String digits = num.replaceAll("[^0-9+]", "");
                boolean dup = false;
                for (String x : p.numbers) if (x.replaceAll("[^0-9+]", "").equals(digits)) dup = true;
                if (!dup) {
                    p.numbers.add(num);
                    p.types.add(q.getInt(3));
                }
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        book = new ArrayList<>(m.values());
        bookAt = System.currentTimeMillis();
        return book;
    }

    /** Reads the contacts in the background so the first spoken name is matched without a wait. */
    static void warm(Context c) {
        final Context app = c.getApplicationContext();
        if (!has(app, Manifest.permission.READ_CONTACTS)) return;
        new Thread(() -> people(app), "voice-contacts").start();
    }

    /** Names already read (never blocks): for the recogniser's hint list. */
    static synchronized List<String> cachedNames(Context c) {
        List<String> out = new ArrayList<>();
        if (book == null) return out;
        for (Person p : book) if (!out.contains(p.name)) out.add(p.name);
        out.addAll(VoiceAliases.load(c).keySet());
        return out;
    }

    /** Contact names (for the speech recogniser to listen for); empty without the contacts permission. */
    static List<String> contactNames(Context c) {
        List<String> out = new ArrayList<>();
        if (!has(c, Manifest.permission.READ_CONTACTS)) return out;
        for (Person p : people(c)) if (!out.contains(p.name)) out.add(p.name);
        for (String alias : VoiceAliases.load(c).keySet()) out.add(alias);
        return out;
    }

    /** Persons whose display name is exactly `name` (ignoring case and accents). */
    private static List<Person> named(Context c, String name) {
        List<Person> out = new ArrayList<>();
        String k = ContactMatcher.key(name);
        for (Person p : people(c)) if (ContactMatcher.key(p.name).equals(k)) out.add(p);
        return out;
    }

    /** What the matcher made of the spoken name(s). */
    private static ContactMatcher.Result lookup(Context c, List<String> heard) {
        List<String> names = new ArrayList<>();
        for (Person p : people(c)) names.add(p.name);
        return ContactMatcher.matchAny(heard, names, VoiceAliases.load(c));
    }

    /** The one number to use for a person: their only number, or their only mobile; null when it is ambiguous. */
    private static String pick(Person p) {
        if (p.numbers.size() == 1) return p.numbers.get(0);
        String mobile = null;
        int count = 0;
        for (int i = 0; i < p.numbers.size(); i++)
            if (p.types.get(i) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) {
                mobile = p.numbers.get(i);
                count++;
            }
        return count == 1 ? mobile : null;
    }

    /** What happened, and, when the contact was only a guess, the question to ask and the command to run on "yes". */
    static final class Outcome {
        final String reply;
        final VoiceCommands.Command confirm; // null unless `reply` is a question
        final String heard;                  // what was said for the contact, to learn as an alias once confirmed
        boolean missed;                      // no contact came close: the caller may try another source (FastBrain's own aliases)

        Outcome(String reply, VoiceCommands.Command confirm, String heard) {
            this.reply = reply;
            this.confirm = confirm;
            this.heard = heard;
        }
    }

    static String run(Context c, VoiceCommands.Command cmd, boolean locked) {
        return exec(c, cmd, locked, null).reply;
    }

    /** `alts`: other things the recogniser thought it heard for the contact name (CALL / MESSAGE only); may be null. */
    static Outcome exec(Context c, VoiceCommands.Command cmd, boolean locked, List<String> alts) {
        if (cmd.type == VoiceCommands.Type.CALL || cmd.type == VoiceCommands.Type.MESSAGE) {
            SharedPreferences sp = c.getSharedPreferences("hm", Context.MODE_PRIVATE);
            if (!sp.getBoolean("voice_enabled", true)) return new Outcome("Voice commands are turned off.", null, null);
            List<String> heard = new ArrayList<>();
            heard.add(cmd.name);
            if (alts != null) for (String a : alts) if (a != null && !a.trim().isEmpty() && !heard.contains(a)) heard.add(a);
            try {
                return cmd.type == VoiceCommands.Type.CALL ? call(c, sp, cmd, heard, locked) : message(c, sp, cmd, heard, locked);
            } catch (Exception e) {
                return new Outcome("Sorry, that didn't work.", null, null);
            }
        }
        return new Outcome(runSimple(c, cmd, locked), null, null);
    }

    private static String runSimple(Context c, VoiceCommands.Command cmd, boolean locked) {
        SharedPreferences sp = c.getSharedPreferences("hm", Context.MODE_PRIVATE);
        if (!sp.getBoolean("voice_enabled", true)) return "Voice commands are turned off.";
        try {
            switch (cmd.type) {
                case MUSIC_PLAY:
                    return media(c, KeyEvent.KEYCODE_MEDIA_PLAY, "Playing.");
                case MUSIC_PAUSE:
                    return media(c, KeyEvent.KEYCODE_MEDIA_PAUSE, "Paused.");
                case MUSIC_NEXT:
                    return media(c, KeyEvent.KEYCODE_MEDIA_NEXT, "Next.");
                case MUSIC_PREV:
                    return media(c, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Previous.");
                case MUSIC_QUERY:
                    if (locked) return "Unlock to play that.";
                    Intent play = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                            .putExtra(android.app.SearchManager.QUERY, cmd.name).putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(play);
                    return "Playing " + cmd.name + ".";
                case TIMER:
                    c.startActivity(new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, cmd.a)
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return "Timer set.";
                case ALARM:
                    c.startActivity(new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, cmd.a).putExtra(AlarmClock.EXTRA_MINUTES, cmd.b)
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return "Alarm set.";
                case TORCH:
                    return torch(c, cmd.a == 1);
                case VOLUME:
                    return volume(c, cmd);
                case TIME:
                    return "It is " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date()) + ".";
                case NAVIGATE:
                    if (locked) return "Unlock to start navigation.";
                    Intent nav = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(cmd.name))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (nav.resolveActivity(c.getPackageManager()) == null) nav = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(cmd.name))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(nav);
                    return "Starting navigation to " + cmd.name + ".";
                case BLOCKED:
                    return "Sorry, I can't do that by voice.";
                default:
                    return "Sorry, I can't do that.";
            }
        } catch (Exception e) {
            return "Sorry, that didn't work.";
        }
    }

    private static String need(Context c, String perm, String what, boolean locked) {
        if (!locked) HermesService.askPermission(c, perm);
        return locked ? "Unlock and allow " + what + " in Hermes first." : "I need permission to " + what + ". Please allow it.";
    }

    /**
     * Who the person meant: a number to use, or the reply to give instead (nobody found, several matches, a guess to
     * confirm). `name` of the Outcome carries the contact's display name when a number was chosen.
     */
    private static final class Target {
        String number, name;
        Outcome stop;
    }

    private static Target target(Context c, VoiceCommands.Command cmd, List<String> heard, boolean locked, String verb) {
        Target t = new Target();
        ContactMatcher.Result r = lookup(c, heard);
        String said = cmd.name;
        if (r.how == ContactMatcher.How.NONE) {
            t.stop = new Outcome("I couldn't find " + said + " in your contacts.", null, null);
            t.stop.missed = true;
            return t;
        }
        if (r.how == ContactMatcher.How.AMBIGUOUS) {
            String l = r.others == null || r.others.size() < 2 ? "" : r.others.get(0) + " or " + r.others.get(1);
            t.stop = new Outcome(locked ? "Several matches. Unlock to choose."
                    : l.isEmpty() ? "I found several contacts called " + said + ". Please " + verb + " by their full name."
                    : "Which one: " + l + "? Please " + verb + " by their full name.", null, null);
            return t;
        }
        if (!r.confident()) {
            // only a guess: never call or text on a guess. Ask, unless the phone is locked.
            if (locked) {
                t.stop = new Outcome("I'm not sure who " + said + " is. Unlock to choose.", null, null);
                return t;
            }
            VoiceCommands.Command yes = new VoiceCommands.Command(cmd.type, r.name, cmd.text, cmd.a, cmd.b);
            t.stop = new Outcome("Did you mean " + r.name + "?", yes, said);
            return t;
        }
        List<Person> hits = named(c, r.name);
        if (hits.isEmpty()) {
            t.stop = new Outcome("I couldn't find " + said + " in your contacts.", null, null);
            return t;
        }
        if (hits.size() > 1) {
            t.stop = new Outcome(locked ? "Several matches. Unlock to choose." : "I found several contacts called " + r.name + ". Please " + verb + " by their full name.", null, null);
            return t;
        }
        String num = pick(hits.get(0));
        if (num == null) {
            t.stop = new Outcome(locked ? "Several numbers. Unlock to choose." : hits.get(0).name + " has several numbers. Please pick one in Contacts.", null, null);
            return t;
        }
        t.number = num;
        t.name = hits.get(0).name;
        return t;
    }

    private static Outcome call(Context c, SharedPreferences sp, VoiceCommands.Command cmd, List<String> heard, boolean locked) {
        if (locked && !sp.getBoolean("voice_calls_locked", true)) return new Outcome("Unlock to make calls.", null, null);
        if (!has(c, Manifest.permission.READ_CONTACTS)) return new Outcome(need(c, Manifest.permission.READ_CONTACTS, "read contacts", locked), null, null);
        if (!has(c, Manifest.permission.CALL_PHONE)) return new Outcome(need(c, Manifest.permission.CALL_PHONE, "make calls", locked), null, null);
        Target t = target(c, cmd, heard, locked, "call");
        if (t.stop != null) return t.stop;
        TelecomManager tm = c.getSystemService(TelecomManager.class);
        tm.placeCall(Uri.fromParts("tel", t.number, null), new Bundle());
        return new Outcome("Calling " + t.name + ".", null, null);
    }

    private static Outcome message(Context c, SharedPreferences sp, VoiceCommands.Command cmd, List<String> heard, boolean locked) {
        if (locked && !sp.getBoolean("voice_msgs_locked", false)) return new Outcome("Unlock to send messages.", null, null);
        if (!has(c, Manifest.permission.READ_CONTACTS)) return new Outcome(need(c, Manifest.permission.READ_CONTACTS, "read contacts", locked), null, null);
        if (!has(c, Manifest.permission.SEND_SMS)) return new Outcome(need(c, Manifest.permission.SEND_SMS, "send messages", locked), null, null);
        if (cmd.text.trim().isEmpty() || cmd.text.length() > 600) return new Outcome("That message is empty or too long.", null, null);
        Target t = target(c, cmd, heard, locked, "use");
        if (t.stop != null) return t.stop;
        SmsManager sm = android.os.Build.VERSION.SDK_INT >= 31 ? c.getSystemService(SmsManager.class) : SmsManager.getDefault();
        sm.sendMultipartTextMessage(t.number, null, sm.divideMessage(cmd.text), null, null);
        return new Outcome("Message sent to " + t.name + ".", null, null);
    }

    private static String media(Context c, int key, String ok) {
        AudioManager am = c.getSystemService(AudioManager.class);
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
        return ok;
    }

    private static String torch(Context c, boolean on) throws Exception {
        CameraManager cm = c.getSystemService(CameraManager.class);
        for (String id : cm.getCameraIdList()) {
            Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (flash != null && flash) {
                cm.setTorchMode(id, on);
                return on ? "Flashlight on." : "Flashlight off.";
            }
        }
        return "This phone has no flashlight.";
    }

    private static String volume(Context c, VoiceCommands.Command cmd) {
        AudioManager am = c.getSystemService(AudioManager.class);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (cmd.b == 1) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * Math.min(100, Math.max(0, cmd.a)) / 100f), AudioManager.FLAG_SHOW_UI);
            return "Volume " + cmd.a + " percent.";
        }
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, cmd.a > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
        return cmd.a > 0 ? "Volume up." : "Volume down.";
    }
}
