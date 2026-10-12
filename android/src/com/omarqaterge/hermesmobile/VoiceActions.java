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

    /** Contacts whose whole display name equals `spoken` (ignoring case and accents), one entry per contact. */
    private static List<Person> exact(Context c, String spoken) {
        String want = VoiceCommands.nameKey(spoken);
        Map<Long, Person> m = new LinkedHashMap<>();
        if (want.isEmpty()) return new ArrayList<>();
        ContentResolver cr = c.getContentResolver();
        try (Cursor q = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[] {ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE}, null, null, null)) {
            int n = 0;
            while (q != null && q.moveToNext() && n++ < 30000) {
                String name = q.getString(1);
                if (name == null || !VoiceCommands.nameKey(name).equals(want)) continue;
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
        return new ArrayList<>(m.values());
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

    static String run(Context c, VoiceCommands.Command cmd, boolean locked) {
        SharedPreferences sp = c.getSharedPreferences("hm", Context.MODE_PRIVATE);
        if (!sp.getBoolean("voice_enabled", true)) return "Voice commands are turned off.";
        try {
            switch (cmd.type) {
                case CALL:
                    return call(c, sp, cmd.name, locked);
                case MESSAGE:
                    return message(c, sp, cmd.name, cmd.text, locked);
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

    private static String call(Context c, SharedPreferences sp, String name, boolean locked) {
        if (locked && !sp.getBoolean("voice_calls_locked", true)) return "Unlock to make calls.";
        if (!has(c, Manifest.permission.READ_CONTACTS)) return need(c, Manifest.permission.READ_CONTACTS, "read contacts", locked);
        if (!has(c, Manifest.permission.CALL_PHONE)) return need(c, Manifest.permission.CALL_PHONE, "make calls", locked);
        List<Person> hits = exact(c, name);
        if (hits.isEmpty()) return "I couldn't find " + name + " in your contacts.";
        if (hits.size() > 1) return locked ? "Several matches. Unlock to choose." : "I found several contacts called " + name + ". Please call by their full name.";
        String num = pick(hits.get(0));
        if (num == null) return locked ? "Several numbers. Unlock to choose." : hits.get(0).name + " has several numbers. Please pick one in Contacts.";
        TelecomManager tm = c.getSystemService(TelecomManager.class);
        tm.placeCall(Uri.fromParts("tel", num, null), new Bundle());
        return "Calling " + hits.get(0).name + ".";
    }

    private static String message(Context c, SharedPreferences sp, String name, String text, boolean locked) {
        if (locked && !sp.getBoolean("voice_msgs_locked", false)) return "Unlock to send messages.";
        if (!has(c, Manifest.permission.READ_CONTACTS)) return need(c, Manifest.permission.READ_CONTACTS, "read contacts", locked);
        if (!has(c, Manifest.permission.SEND_SMS)) return need(c, Manifest.permission.SEND_SMS, "send messages", locked);
        List<Person> hits = exact(c, name);
        if (hits.isEmpty()) return "I couldn't find " + name + " in your contacts.";
        if (hits.size() > 1) return locked ? "Several matches. Unlock to choose." : "I found several contacts called " + name + ". Please use their full name.";
        String num = pick(hits.get(0));
        if (num == null) return locked ? "Several numbers. Unlock to choose." : hits.get(0).name + " has several numbers. Please pick one in Contacts.";
        if (text.trim().isEmpty() || text.length() > 600) return "That message is empty or too long.";
        SmsManager sm = android.os.Build.VERSION.SDK_INT >= 31 ? c.getSystemService(SmsManager.class) : SmsManager.getDefault();
        sm.sendMultipartTextMessage(num, null, sm.divideMessage(text), null, null);
        return "Message sent to " + hits.get(0).name + ".";
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
