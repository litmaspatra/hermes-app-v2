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
        // The same person saved under several accounts (Google, SIM, WhatsApp) comes as several contacts with one name and
        // the same number: that is one person, not "several contacts called X".
        List<Person> merged = new ArrayList<>();
        for (Person p : m.values()) {
            Person into = null;
            for (Person q : merged) if (ContactMatcher.key(q.name).equals(ContactMatcher.key(p.name)) && sharesNumber(q, p)) {
                into = q;
                break;
            }
            if (into == null) {
                merged.add(p);
                continue;
            }
            for (int i = 0; i < p.numbers.size(); i++) {
                boolean have = false;
                for (String x : into.numbers) if (tail(x).equals(tail(p.numbers.get(i)))) have = true;
                if (!have) {
                    into.numbers.add(p.numbers.get(i));
                    into.types.add(p.types.get(i));
                }
            }
        }
        book = merged;
        bookAt = System.currentTimeMillis();
        return book;
    }

    /** The last 10 digits of a number: +91 98xxxxxx21 and 098xxxxxx21 are the same phone. */
    static String tail(String n) {
        String d = n == null ? "" : n.replaceAll("[^0-9]", "");
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    private static boolean sharesNumber(Person a, Person b) {
        for (String x : a.numbers) for (String y : b.numbers) if (tail(x).equals(tail(y))) return true;
        return false;
    }

    /** "Which one is it?" was answered once for two contacts that share a name: use that number from now on. */
    static void pin(Context c, String name, String number) {
        c.getSharedPreferences("hm", Context.MODE_PRIVATE).edit().putString("voice_pin_" + ContactMatcher.key(name), tail(number)).apply();
    }

    private static String pinned(Context c, String name) {
        return c.getSharedPreferences("hm", Context.MODE_PRIVATE).getString("voice_pin_" + ContactMatcher.key(name), "");
    }

    /** Reads the contacts in the background so the first spoken name is matched without a wait. */
    static void warm(Context c) {
        final Context app = c.getApplicationContext();
        VoiceAliases.syncFromMemory(app); // the names memory already knows, before the first name is spoken
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
        List<VoiceCommands.Command> options; // several equally good contacts: the reply asks which, and the answer picks one of these
        List<String> labels;                 // what each option is called when spoken, in the same order

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

    private static String asking(List<String> labels) {
        StringBuilder b = new StringBuilder("Which one: ");
        for (int i = 0; i < labels.size(); i++) b.append(i == 0 ? "" : i == labels.size() - 1 ? " or " : ", ").append(labels.get(i));
        return b.append("?").toString();
    }

    private static Target target(Context c, VoiceCommands.Command cmd, List<String> heard, boolean locked, String verb) {
        Target t = new Target();
        if (!cmd.number.isEmpty()) { // the person already chose between two contacts of the same name
            for (Person p : named(c, cmd.name)) for (String n : p.numbers) if (tail(n).equals(tail(cmd.number))) {
                t.number = n;
                t.name = p.name;
                return t;
            }
        }
        ContactMatcher.Result r = lookup(c, heard);
        String said = cmd.name;
        if (r.how == ContactMatcher.How.NONE) {
            t.stop = new Outcome("I couldn't find " + said + " in your contacts.", null, null);
            t.stop.missed = true;
            return t;
        }
        if (r.how == ContactMatcher.How.AMBIGUOUS) {
            if (locked || r.others == null || r.others.size() < 2) {
                t.stop = new Outcome(locked ? "Several matches. Unlock to choose." : "I found several contacts called " + said + ". Please " + verb + " by their full name.", null, null);
                return t;
            }
            // Ask once; the answer is remembered as an alias (and in memory), so this question never comes again.
            List<VoiceCommands.Command> opts = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (String n : r.others) {
                if (opts.size() >= 4) break;
                opts.add(new VoiceCommands.Command(cmd.type, n, cmd.text, cmd.a, cmd.b));
                labels.add(n);
            }
            t.stop = new Outcome(asking(labels), null, said);
            t.stop.options = opts;
            t.stop.labels = labels;
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
            String pin = pinned(c, r.name);
            if (!pin.isEmpty()) for (Person p : hits) for (String n : p.numbers) if (tail(n).equals(pin)) {
                t.number = n;
                t.name = p.name;
                return t;
            }
            if (locked) {
                t.stop = new Outcome("Several matches. Unlock to choose.", null, null);
                return t;
            }
            // Two different people saved under one name: tell them apart by the end of their number, ask once, remember.
            List<VoiceCommands.Command> opts = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (Person p : hits) {
                if (opts.size() >= 4) break;
                String num = pick(p) != null ? pick(p) : p.numbers.isEmpty() ? "" : p.numbers.get(0);
                String t4 = tail(num);
                opts.add(new VoiceCommands.Command(cmd.type, p.name, cmd.text, cmd.a, cmd.b, num));
                labels.add(p.name + " ending " + (t4.length() >= 4 ? t4.substring(t4.length() - 4) : t4));
            }
            t.stop = new Outcome(asking(labels), null, said);
            t.stop.options = opts;
            t.stop.labels = labels;
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
