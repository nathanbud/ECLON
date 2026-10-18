package ph.coop.core.accounting;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import org.hibernate.annotations.Immutable;

/** Read-only view of the legacy chart of accounts ({@code coa}). */
@Entity
@Immutable
@Table(name = "coa")
public class Account {

    @Embeddable
    public record Id(
        @Column(name = "COABR_CODE") int branchCode,
        @Column(name = "COAID") String code) implements Serializable {}

    @EmbeddedId private Id id;
    @Column(name = "COADesc") private String description;
    @Column(name = "COAacct_type") private Integer accountType;
    @Column(name = "COAacct_lvl") private String level;
    @Column(name = "COAacct_sum") private String summaryAccount;
    @Column(name = "COABALTYPE") private Integer balanceType;
    @Column(name = "COAisdisabled") private Integer disabled;

    protected Account() {}

    public Id getId() { return id; }
    public String getDescription() { return description; }
    public Integer getAccountType() { return accountType; }
    public String getLevel() { return level; }
    public String getSummaryAccount() { return summaryAccount; }
    public Integer getBalanceType() { return balanceType; }
    public Integer getDisabled() { return disabled; }
}
