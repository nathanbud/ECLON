package ph.coop.core.member;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Name search for the Find Client box, in three steps that stop as soon as a page is full and never count the whole table:
 * <ol>
 *   <li>names whose last name starts with the first word (uses the NAME index),</li>
 *   <li>names whose first name starts with it (uses idx_client_fname),</li>
 *   <li>only when those do not fill the page: the broad search, where a word may start any later word of a name or sit anywhere in the account name (a scan).</li>
 * </ol>
 * Every further word must start the last, first or middle name, in any order, so "Nathaniel Budikey", "Budikey, Nathaniel" and "nat bud" all find the same client.
 * The client columns are latin1_swedish_ci, so a plain LIKE is case-insensitive and can use the indexes (lower() would stop that). On PostgreSQL the words will need
 * lower() or ilike and a matching index. Unless {@code all} is true only active members (account status 1) are returned.
 */
public interface MemberRepository extends JpaRepository<Member, Member.Id> {

    String OTHER_WORDS = """
              and (:t2 = '' or m.lastName like concat(:t2, '%') or m.firstName like concat(:t2, '%') or m.middleName like concat(:t2, '%')
                            or m.lastName like concat('% ', :t2, '%') or m.firstName like concat('% ', :t2, '%') or m.middleName like concat('% ', :t2, '%'))
              and (:t3 = '' or m.lastName like concat(:t3, '%') or m.firstName like concat(:t3, '%') or m.middleName like concat(:t3, '%')
                            or m.lastName like concat('% ', :t3, '%') or m.firstName like concat('% ', :t3, '%') or m.middleName like concat('% ', :t3, '%'))
              and (:all = true or m.accountStatus = 1)
            """;

    @Query("select m from Member m where m.lastName like concat(:t1, '%')" + OTHER_WORDS)
    Slice<Member> byLastNamePrefix(@Param("t1") String t1, @Param("t2") String t2, @Param("t3") String t3, @Param("all") boolean all, Pageable pageable);

    @Query("select m from Member m where m.firstName like concat(:t1, '%')" + OTHER_WORDS)
    Slice<Member> byFirstNamePrefix(@Param("t1") String t1, @Param("t2") String t2, @Param("t3") String t3, @Param("all") boolean all, Pageable pageable);

    @Query("""
        select m from Member m
        where ((:t1 = '' or m.lastName like concat(:t1, '%') or m.firstName like concat(:t1, '%') or m.middleName like concat(:t1, '%')
                         or m.lastName like concat('% ', :t1, '%') or m.firstName like concat('% ', :t1, '%') or m.middleName like concat('% ', :t1, '%')
              )""" + OTHER_WORDS + """
              or m.accountName like concat('%', :q, '%') and (:all = true or m.accountStatus = 1))
        """)
    Slice<Member> broad(@Param("q") String q, @Param("t1") String t1, @Param("t2") String t2, @Param("t3") String t3, @Param("all") boolean all, Pageable pageable);

    List<Member> findByIdClientId(int clientId);

    List<Member> findByIdBranchCodeAndIdClientId(int branchCode, int clientId);

    List<Member> findByReferenceIdIgnoreCase(String referenceId);

    default Page<Member> search(String query, boolean includeInactive, Pageable pageable) {
        // Drop LIKE wildcards and periods (so the initial in "Nathaniel O. Budikey" is just "o"), then split on spaces and commas.
        String q = query.replaceAll("[%_.]", "").trim().toLowerCase();
        String[] w = q.isEmpty() ? new String[0] : q.split("[\\s,]+");
        w = Arrays.copyOf(w, 3);
        String t1 = nz(w[0]), t2 = nz(w[1]), t3 = nz(w[2]);
        int size = pageable.getPageSize();
        // One extra row tells whether there is more, so nothing has to be counted.
        Pageable top = PageRequest.of(pageable.getPageNumber(), size + 1, pageable.getSort());

        if (pageable.getPageNumber() == 0 && !t1.isEmpty()) {
            Pageable first = PageRequest.of(0, size + 1, pageable.getSort());
            List<Member> merged = merge(byLastNamePrefix(t1, t2, t3, includeInactive, first).getContent(), byFirstNamePrefix(t1, t2, t3, includeInactive, first).getContent(), size + 1);
            if (merged.size() >= size) return asPage(merged, pageable);
        }
        return asPage(broad(q, t1, t2, t3, includeInactive, top).getContent(), pageable);
    }

    /** The two lists are each the newest-name-first top of their own query, so the top of their union is within them. Sorted by last then first name, without repeats. */
    private static List<Member> merge(List<Member> a, List<Member> b, int limit) {
        Map<Member.Id, Member> unique = new LinkedHashMap<>();
        for (Member m : a) unique.putIfAbsent(m.getId(), m);
        for (Member m : b) unique.putIfAbsent(m.getId(), m);
        Comparator<String> text = Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER);
        List<Member> out = new ArrayList<>(unique.values());
        out.sort(Comparator.comparing(Member::getLastName, text).thenComparing(Member::getFirstName, text).thenComparing((m) -> m.getId().clientId()));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** A page from up to size+1 rows: the extra row only says there is a next page. The total is then a lower bound, enough for "more results". */
    private static Page<Member> asPage(List<Member> rows, Pageable pageable) {
        int size = pageable.getPageSize();
        List<Member> content = rows.size() > size ? rows.subList(0, size) : rows;
        long total = pageable.getOffset() + content.size() + (rows.size() > size ? 1 : 0);
        return new PageImpl<>(new ArrayList<>(content), pageable, total);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
