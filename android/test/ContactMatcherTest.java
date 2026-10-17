package com.omarqaterge.hermesmobile;

import com.omarqaterge.hermesmobile.ContactMatcher.How;
import com.omarqaterge.hermesmobile.ContactMatcher.Result;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Plain-Java test (no JUnit): run by tools/java-unit-tests.sh. */
public class ContactMatcherTest {
    static int n = 0;

    static void is(String heard, List<String> names, Map<String, String> al, How how, String name) {
        n++;
        Result r = ContactMatcher.match(heard, names, al);
        if (r.how != how || (name != null && !name.equals(r.name))) fail("[" + heard + "] expected " + how + "(" + name + ") got " + r);
    }

    static void fail(String m) {
        System.err.println("FAIL: " + m);
        System.exit(1);
    }

    public static void main(String[] args) {
        List<String> names = Arrays.asList("Rahul Sharma", "Priya Verma", "Budagodira Ramesh", "Amit Kumar", "Amit Singh", "Neha", "Papa", "Dr. Mehta", "Sandeep Bhagwati", "Rohit Jain");
        Map<String, String> al = new HashMap<>();
        al.put("daddy", "Papa");
        al.put("boss", "Dr. Mehta");

        is("Rahul Sharma", names, null, How.EXACT, "Rahul Sharma");
        is("rahul sharma", names, null, How.EXACT, "Rahul Sharma");
        is("Rahul", names, null, How.FIRST_NAME, "Rahul Sharma");     // one Rahul: no need to say the surname
        is("Amit", names, null, How.AMBIGUOUS, null);                  // two Amits: ask
        is("daddy", names, al, How.ALIAS, "Papa");
        is("Boss", names, al, How.ALIAS, "Dr. Mehta");
        is("daddy", names, null, How.NONE, null);                      // no alias saved, no such contact

        // misheard names: the case that said "I couldn't find it"
        is("butagorira", names, null, How.SOUNDS_LIKE, "Budagodira Ramesh");
        is("budagodira ramesh", names, null, How.EXACT, "Budagodira Ramesh");
        is("Bagavati", names, null, How.SOUNDS_LIKE, "Sandeep Bhagwati");  // surname alone, spelled differently
        is("Rahool", names, null, How.SOUNDS_LIKE, "Rahul Sharma");
        is("Priyaa Varma", names, null, How.SOUNDS_LIKE, "Priya Verma");

        // it must NOT guess wildly
        is("Suresh", names, null, How.NONE, null);
        is("Rakesh", names, null, How.NONE, null);
        is("Mohan Lal", names, null, How.NONE, null);
        is("", names, null, How.NONE, null);
        is("x", Arrays.asList(), null, How.NONE, null);

        // several guesses from the recogniser: the one that matches wins
        n++;
        Result r = ContactMatcher.matchAny(Arrays.asList("buy a go dear a", "butagorira", "but a gory"), names, null);
        if (r.how != How.SOUNDS_LIKE || !"Budagodira Ramesh".equals(r.name)) fail("matchAny got " + r);

        // phonetic folding
        n++;
        if (!ContactMatcher.skeleton("Bhagwati").equals(ContactMatcher.skeleton("bagavati"))) fail("skeleton " + ContactMatcher.skeleton("Bhagwati") + " vs " + ContactMatcher.skeleton("bagavati"));
        // alias text round trip
        n++;
        Map<String, String> m = ContactMatcher.parseAliases("Papa = Ramesh Sharma\n# a comment\nboss=Dr. Mehta  # trailing\nnonsense line\n = nobody\n");
        if (m.size() != 2 || !"Ramesh Sharma".equals(m.get("papa")) || !"Dr. Mehta".equals(m.get("boss"))) fail("aliases " + m);
        n++;
        if (!ContactMatcher.parseAliases(ContactMatcher.formatAliases(m)).equals(m)) fail("alias round trip");
        // "Which one: A or B?" answers
        java.util.List<String> opt = java.util.Arrays.asList("Rahul Sharma", "Rahul Verma", "Rahul Singh");
        String[][] picks = {{"the second one", "Rahul Verma"}, {"first", "Rahul Sharma"}, {"Verma", "Rahul Verma"}, {"call Singh", "Rahul Singh"},
                {"the last one", "Rahul Singh"}, {"dusra", "Rahul Verma"}, {"Rahul Sharma", "Rahul Sharma"}, {"rahul", null}, {"banana", null}};
        for (String[] pk : picks) {
            n++;
            String got = ContactMatcher.pickOption(pk[0], opt);
            if (pk[1] == null ? got != null : !pk[1].equals(got)) fail("pickOption(" + pk[0] + ") = " + got);
        }
        n++;
        if (!"Rahul Sharma ending 4821".equals(ContactMatcher.pickOption("the first", java.util.Arrays.asList("Rahul Sharma ending 4821", "Rahul Sharma ending 1935")))) fail("same-name first");
        n++;
        if (!"Rahul Sharma ending 1935".equals(ContactMatcher.pickOption("1935", java.util.Arrays.asList("Rahul Sharma ending 4821", "Rahul Sharma ending 1935")))) fail("same-name digits");
        System.out.println("ContactMatcherTest: " + n + " cases passed");
    }
}
