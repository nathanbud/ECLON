package ph.coop.core.member;

import java.util.Arrays;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, Member.Id> {

    /**
     * Each of up to three search words must start a word in the last, first or middle name, in any order,
     * so "Nathaniel Budikey", "Budikey, Nathaniel" and "nat bud" all find the same client. An empty word
     * matches everything. The whole query is also matched anywhere inside the account name.
     */
    @Query("""
        select m from Member m
        where (
              (:t1 = '' or lower(m.lastName) like concat(:t1, '%') or lower(m.lastName) like concat('% ', :t1, '%')
                        or lower(m.firstName) like concat(:t1, '%') or lower(m.firstName) like concat('% ', :t1, '%')
                        or lower(m.middleName) like concat(:t1, '%') or lower(m.middleName) like concat('% ', :t1, '%'))
          and (:t2 = '' or lower(m.lastName) like concat(:t2, '%') or lower(m.lastName) like concat('% ', :t2, '%')
                        or lower(m.firstName) like concat(:t2, '%') or lower(m.firstName) like concat('% ', :t2, '%')
                        or lower(m.middleName) like concat(:t2, '%') or lower(m.middleName) like concat('% ', :t2, '%'))
          and (:t3 = '' or lower(m.lastName) like concat(:t3, '%') or lower(m.lastName) like concat('% ', :t3, '%')
                        or lower(m.firstName) like concat(:t3, '%') or lower(m.firstName) like concat('% ', :t3, '%')
                        or lower(m.middleName) like concat(:t3, '%') or lower(m.middleName) like concat('% ', :t3, '%'))
        ) or lower(m.accountName) like concat('%', :q, '%')
        """)
    Page<Member> searchByWords(@Param("q") String q, @Param("t1") String t1, @Param("t2") String t2,
                               @Param("t3") String t3, Pageable pageable);

    List<Member> findByIdClientId(int clientId);

    List<Member> findByIdBranchCodeAndIdClientId(int branchCode, int clientId);

    List<Member> findByReferenceIdIgnoreCase(String referenceId);

    default Page<Member> search(String query, Pageable pageable) {
        // Drop LIKE wildcards and periods (so the initial in "Nathaniel O. Budikey" is just "o"), then split on spaces and commas.
        String q = query.replaceAll("[%_.]", "").trim().toLowerCase();
        String[] w = q.isEmpty() ? new String[0] : q.split("[\\s,]+");
        w = Arrays.copyOf(w, 3);
        return searchByWords(q, nz(w[0]), nz(w[1]), nz(w[2]), pageable);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
