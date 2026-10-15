package com.omarqaterge.hermesmobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides what a spoken sentence does, fastest path first:
 *  1. things the phone can do by itself in an instant (flashlight, volume, timer, time, navigation, pause/next);
 *  2. calls and messages: matched against your contacts here (aliases, first names, sound-alikes), at once;
 *     only when nothing here fits is FastBrain asked (it has its own aliases and memory);
 *  3. music and everything else: FastBrain's router (local skills: play a song, open an app, battery...);
 *  4. what FastBrain doesn't handle goes to Hermes as a question (when allowed), which can hold a conversation.
 * Runs on a background thread (FastBrain is a network call); the answer comes back on the main thread.
 */
final class VoiceRouter {
    private VoiceRouter() {}

    static final class Result {
        String reply = "";
        boolean toHermes;                    // hand the sentence to Hermes
        String hermesText = "";
        VoiceCommands.Command confirm;       // a guess to confirm: run this on "yes"
        String heard;                        // what was said for it, to remember as an alias after "yes"
        boolean fbConfirm;                   // FastBrain asked "Did you mean ...?": send it the yes / no
        boolean command;                     // a task for Hermes (play a song), not a chat: no conversation afterwards
        String how = "native";               // for History: native | fastbrain | hermes
        String detail = "";
    }

    interface Done {
        void on(Result r);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** What FastBrain may run by voice, and what it needs the phone unlocked for. */
    private static final Set<String> FB_OK = new HashSet<>(Arrays.asList("call_contact", "send_sms", "send_whatsapp", "open_app", "torch_on", "torch_off",
            "battery_status", "wifi_status", "set_volume", "play_music", "choose_music_from_context", "play_video", "pause_music", "resume_music",
            "create_reminder", "create_obsidian_note"));
    private static final Set<String> FB_LOCKED_OK = new HashSet<>(Arrays.asList("call_contact", "send_sms", "send_whatsapp", "torch_on", "torch_off",
            "battery_status", "wifi_status", "set_volume", "pause_music", "resume_music"));
    private static final Pattern PRIVATE = Pattern.compile(".*\\b(camera|selfie|photo|picture|video\\s+record|screenshot|notifications?|read\\s+(?:my\\s+)?(?:messages?|sms|mail|email)|my\\s+messages)\\b.*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    static void route(Context c, List<String> alts, boolean locked, Done done) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            Result r;
            try {
                r = decide(app, alts, locked);
            } catch (Exception e) {
                r = new Result();
                r.reply = "Sorry, that didn't work.";
                r.detail = String.valueOf(e);
            }
            final Result out = r;
            MAIN.post(() -> done.on(out));
        }, "voice-route").start();
    }

    /** Same, for a short yes / no answer to a question FastBrain asked: its router remembers what it asked. */
    static void answerFastBrain(Context c, String text, boolean locked, Done done) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            Result r = viaFastBrain(app, text, locked);
            if (r == null) {
                r = new Result();
                r.reply = "Sorry, that didn't work.";
            }
            final Result out = r;
            MAIN.post(() -> done.on(out));
        }, "voice-route").start();
    }

    private static Result decide(Context c, List<String> alts, boolean locked) {
        String text = alts.get(0);
        VoiceCommands.Command cmd = VoiceCommands.parse(text);
        boolean fb = FastBrainClient.enabled(c);
        Result r = new Result();
        r.detail = cmd.type.name();
        switch (cmd.type) {
            case BLOCKED:
                r.reply = "Sorry, I can't do that by voice.";
                return r;
            case CALL:
            case MESSAGE: {
                List<String> names = new ArrayList<>();
                for (String a : alts) {
                    VoiceCommands.Command k = VoiceCommands.parse(a);
                    if (k.type == cmd.type && !k.name.isEmpty()) names.add(k.name);
                }
                VoiceActions.Outcome o = VoiceActions.exec(c, cmd, locked, names);
                // Not a contact here at all, or only a guess: FastBrain may know the name (its own aliases and memory).
                if (fb && !locked && (o.missed || o.confirm != null)) {
                    Result f = viaFastBrain(c, text, locked);
                    if (f != null && !f.reply.isEmpty()) return f;
                }
                r.reply = o.reply;
                r.confirm = o.confirm;
                r.heard = o.heard;
                return r;
            }
            case MUSIC_PLAY:
            case MUSIC_QUERY: {
                if (fb) {
                    Result f = viaFastBrain(c, text, locked);
                    if (f != null) return f;
                }
                // Only "... on YouTube" is a YouTube search. Anything else the router didn't take ("play a song", a
                // vague mood) is for Hermes: its FastBrain pre-router (Gemini for fuzzy requests, Hindsight Lite,
                // the music helper) plays it in the music app you set up; opening YouTube would ignore all of that.
                boolean youtube = text.toLowerCase(Locale.ROOT).contains("youtube");
                if (!youtube && !locked && c.getSharedPreferences("hm", Context.MODE_PRIVATE).getBoolean("voice_hermes", true)) {
                    r.toHermes = true;
                    r.command = true;
                    r.hermesText = text;
                    r.how = "hermes";
                    return r;
                }
                r.reply = VoiceActions.run(c, cmd, locked);
                return r;
            }
            case UNKNOWN: {
                if (fb) {
                    Result f = viaFastBrain(c, text, locked);
                    if (f != null) return f;
                }
                if (PRIVATE.matcher(text).matches()) {
                    r.reply = "Sorry, I can't do that by voice.";
                    return r;
                }
                if (!c.getSharedPreferences("hm", Context.MODE_PRIVATE).getBoolean("voice_hermes", true)) {
                    r.reply = "Sorry, I can't do that.";
                    return r;
                }
                if (locked) {
                    r.reply = "Unlock to ask Hermes.";
                    return r;
                }
                r.toHermes = true;
                r.hermesText = text;
                r.how = "hermes";
                return r;
            }
            default:
                r.reply = VoiceActions.run(c, cmd, locked);
                return r;
        }
    }

    /** One FastBrain round trip (route, check the skill is allowed, run it). Null = FastBrain isn't handling this. */
    static Result viaFastBrain(Context c, String text, boolean locked) {
        if (!FastBrainClient.enabled(c)) return null;
        FastBrainClient.Reply peek = FastBrainClient.post(c, text, false, 4000);
        if (!peek.reachable) return null;
        JSONObject p = peek.payload;
        String skill = p.optString("skill", "");
        if (!"local".equals(p.optString("route")) || !FB_OK.contains(skill)) return null;
        Result r = new Result();
        r.how = "fastbrain";
        r.detail = skill;
        android.content.SharedPreferences sp = c.getSharedPreferences("hm", Context.MODE_PRIVATE);
        if (PRIVATE.matcher(text).matches()) {
            r.reply = "Sorry, I can't do that by voice.";
            return r;
        }
        if (locked) {
            if (!FB_LOCKED_OK.contains(skill)) {
                r.reply = "Unlock to do that.";
                return r;
            }
            if ("call_contact".equals(skill) && !sp.getBoolean("voice_calls_locked", true)) {
                r.reply = "Unlock to make calls.";
                return r;
            }
            if (skill.startsWith("send_") && !sp.getBoolean("voice_msgs_locked", false)) {
                r.reply = "Unlock to send messages.";
                return r;
            }
        }
        FastBrainClient.Reply run = FastBrainClient.post(c, text, true, 25_000);
        if (!run.reachable) {
            r.reply = "That is taking too long.";
            return r;
        }
        JSONObject ex = run.payload.optJSONObject("execution");
        JSONObject args = run.payload.optJSONObject("arguments");
        JSONObject guard = ex == null ? null : ex.optJSONObject("contact_guard");
        // A name it could not match: it keeps the question and the guess, and a "yes" next completes it.
        if ("contact_uncertain".equals(ex == null ? "" : ex.optString("error")) || guard != null && "contact_uncertain".equals(guard.optString("status"))) {
            JSONArray sug = guard == null ? null : guard.optJSONArray("suggestions");
            String heard = guard == null ? "" : guard.optString("heard", "");
            if (sug != null && sug.length() > 0 && sug.optJSONObject(0) != null) {
                r.reply = "Did you mean " + sug.optJSONObject(0).optString("name") + "?";
                r.fbConfirm = true;
            } else {
                r.reply = "I couldn't find " + (heard.isEmpty() ? "that contact" : heard) + " in your contacts.";
            }
            return r;
        }
        boolean ok = ex != null && ex.optBoolean("executed") && ex.optBoolean("success");
        if (!ok) {
            String why = ex == null ? "" : ex.optString("error", "");
            r.reply = "missing_contact".equals(guard == null ? "" : guard.optString("status")) ? "Who should I " + (skill.equals("call_contact") ? "call" : "message") + "?"
                    : "That didn't work.";
            r.detail = skill + (why.isEmpty() ? "" : ": " + why);
            return r;
        }
        r.reply = spoken(skill, args, guard, ex);
        return r;
    }

    private static String arg(JSONObject a, String k) {
        return a == null ? "" : a.optString(k, "").trim();
    }

    private static String spoken(String skill, JSONObject args, JSONObject guard, JSONObject ex) {
        String who = guard != null && !guard.optString("contact", "").isEmpty() ? guard.optString("contact") : arg(args, "contact");
        switch (skill) {
            case "call_contact":
                return who.isEmpty() ? "Calling." : "Calling " + who + ".";
            case "send_sms":
            case "send_whatsapp":
                return who.isEmpty() ? "Sent." : "Message sent to " + who + ".";
            case "battery_status": {
                try {
                    JSONObject b = new JSONObject(ex.optString("stdout", "{}"));
                    if (b.has("percentage")) return "Battery is at " + b.optInt("percentage") + " percent" + ("CHARGING".equalsIgnoreCase(b.optString("status")) ? ", charging." : ".");
                } catch (Exception ignored) {
                }
                return "Done.";
            }
            case "torch_on":
                return "Flashlight on.";
            case "torch_off":
                return "Flashlight off.";
            case "pause_music":
                return "Paused.";
            case "resume_music":
                return "Playing.";
            case "play_music":
            case "play_video":
            case "choose_music_from_context": {
                String q = arg(args, "query");
                return q.isEmpty() ? "Playing." : "Playing " + q + ".";
            }
            case "open_app":
                return arg(args, "app").isEmpty() ? "Opening." : "Opening " + arg(args, "app") + ".";
            case "set_volume":
                return arg(args, "level").isEmpty() ? "Done." : "Volume " + arg(args, "level").toLowerCase(Locale.ROOT) + " percent.";
            case "create_reminder":
                return "Reminder saved.";
            case "create_obsidian_note":
                return "Note saved.";
            default:
                return "Done.";
        }
    }
}
