package ph.coop.core.member;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.hibernate.annotations.Immutable;

/** Read-only view of the legacy {@code client} table (member master). Only the commonly used columns are mapped. */
@Entity
@Immutable
@Table(name = "client")
public class Member {

    @Embeddable
    public record Id(
        @Column(name = "ClientIDBrCode") int branchCode,
        @Column(name = "ClientID") int clientId) implements Serializable {}

    @EmbeddedId private Id id;
    @Column(name = "FName") private String firstName;
    @Column(name = "MName") private String middleName;
    @Column(name = "LName") private String lastName;
    @Column(name = "SName") private String suffix;
    @Column(name = "AccountName") private String accountName;
    @Column(name = "DateOpened") private LocalDateTime dateOpened;
    @Column(name = "ClientType") private Integer clientType;
    @Column(name = "AccountStatus") private Integer accountStatus;
    @Column(name = "DateOfBirth") private LocalDate dateOfBirth;
    @Column(name = "Gender") private Integer gender;
    @Column(name = "CivilStatus") private Integer civilStatus;
    @Column(name = "CellNum") private String cellNumber;
    @Column(name = "EmailAddress") private String email;
    @Column(name = "ResAddStreet") private String street;
    @Column(name = "ResAddBarangay") private String barangay;
    @Column(name = "ResAddCity") private String city;
    @Column(name = "ResAddProvince") private String province;
    @Column(name = "ClosedDate") private LocalDateTime closedDate;
    @Column(name = "UpdDateTime") private LocalDateTime updatedAt;
    @Column(name = "OldClientID") private String referenceId;
    @Column(name = "AccountType") private Integer accountType;
    @Column(name = "AlertLevel") private Integer alertLevel;
    @Column(name = "ClientDEPT") private Integer department;
    @Column(name = "Profession") private String profession;
    @Column(name = "ReligionID") private Integer religion;
    @Column(name = "MailingAddress") private Integer mailingAddress;
    @Column(name = "DOSRITag") private Integer dosriTag;
    @Column(name = "IsIncludeHealthCare") private Integer includeHealthCare;
    @Column(name = "BusinessIncome") private String monthlyIncome;
    @Column(name = "ResAddZipCode") private String zipCode;
    @Column(name = "ResSince") private Integer residenceSince;
    @Column(name = "ResType") private Integer residenceType;
    @Column(name = "ProvAddStreet") private String provStreet;
    @Column(name = "ProvAddBarangay") private String provBarangay;
    @Column(name = "ProvAddCity") private String provCity;
    @Column(name = "ProvAddProvince") private String provProvince;

    protected Member() {}

    public Id getId() { return id; }
    public String getFirstName() { return firstName; }
    public String getMiddleName() { return middleName; }
    public String getLastName() { return lastName; }
    public String getSuffix() { return suffix; }
    public String getAccountName() { return accountName; }
    public LocalDateTime getDateOpened() { return dateOpened; }
    public Integer getClientType() { return clientType; }
    public Integer getAccountStatus() { return accountStatus; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public Integer getGender() { return gender; }
    public Integer getCivilStatus() { return civilStatus; }
    public String getCellNumber() { return cellNumber; }
    public String getEmail() { return email; }
    public String getStreet() { return street; }
    public String getBarangay() { return barangay; }
    public String getCity() { return city; }
    public String getProvince() { return province; }
    public LocalDateTime getClosedDate() { return closedDate; }
    public String getReferenceId() { return referenceId; }
    public Integer getAccountType() { return accountType; }
    public Integer getAlertLevel() { return alertLevel; }
    public Integer getDepartment() { return department; }
    public String getProfession() { return profession; }
    public Integer getReligion() { return religion; }
    public Integer getMailingAddress() { return mailingAddress; }
    public Integer getDosriTag() { return dosriTag; }
    public Integer getIncludeHealthCare() { return includeHealthCare; }
    public String getMonthlyIncome() { return monthlyIncome; }
    public String getZipCode() { return zipCode; }
    public Integer getResidenceSince() { return residenceSince; }
    public Integer getResidenceType() { return residenceType; }
    public String getProvStreet() { return provStreet; }
    public String getProvBarangay() { return provBarangay; }
    public String getProvCity() { return provCity; }
    public String getProvProvince() { return provProvince; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
