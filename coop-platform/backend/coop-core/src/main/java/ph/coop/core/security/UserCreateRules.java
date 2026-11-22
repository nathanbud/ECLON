package ph.coop.core.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** What creating a user must satisfy, and what is never copied from the user whose permissions are copied. Pure logic, no database access. */
final class UserCreateRules {

    private UserCreateRules() {}

    static final int NAME_MAX = 45;

    /**
     * The access columns a new user never takes from the user copied: who they are, their level, branch and member number, whether they are switched off, the old
     * system's password, one-time-code and password-age columns, and dates that belong to the other person. Lowercase, to compare with any database's spelling.
     */
    static final Set<String> NOT_COPIED = Set.of(
        "accessid", "accessusername", "accessfname", "accessmname", "accesslname", "accesslevel", "accessbr_code", "accessclientid", "accessisdisabled",
        "accesspassword", "accessmtmpwd", "accessotpsecretkey", "accessrequireloginotp", "accessrequireloginotpauthenticator", "accesslastchangepassdate",
        "accessexpirydate", "accessdate", "accesstime");

    /** Why the new user cannot be created as asked, empty when they can. Uniqueness of the username is checked against the database by the caller. */
    static List<String> problems(String username, String firstName, String lastName, String middleName, String level, String callerLevel) {
        List<String> out = new ArrayList<>();
        String u = username == null ? "" : username.trim();
        if (u.length() < 3 || u.length() > 30) out.add("The username must be 3 to 30 characters.");
        else if (!u.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) out.add("The username may have letters, digits, dots, dashes and underscores only, and must start with a letter or digit.");
        if (firstName == null || firstName.isBlank()) out.add("Give the first name.");
        if (lastName == null || lastName.isBlank()) out.add("Give the last name.");
        for (String n : new String[] {firstName, middleName, lastName}) {
            if (n != null && n.trim().length() > NAME_MAX) {
                out.add("A name can have at most " + NAME_MAX + " characters.");
                break;
            }
        }
        String lv = level == null ? "" : level.trim().toUpperCase();
        if (!UserEditRules.LEVELS.contains(lv)) out.add("Choose the level: USER, ADMIN or SUPERADMIN.");
        else if (!lv.equals("USER") && !"SUPERADMIN".equalsIgnoreCase(callerLevel)) out.add("Only a super administrator can create an administrator.");
        return out;
    }
}
