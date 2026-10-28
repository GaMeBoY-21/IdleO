package com.idleo.controlplane.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.IdGenerator;
import com.idleo.controlplane.LegalSettings;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.Paise;
import com.idleo.controlplane.TestAdmins;
import com.idleo.controlplane.TestRazorpayConfiguration;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.TestUsers.TestUser;
import com.idleo.controlplane.billing.SessionCharges;
import com.idleo.controlplane.identity.AuditTrail;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Invoices and GST (ADR 0039): the GSTIN's check character, GST split by state, documents for each invoice mode,
 * numbers gap-free and never repeated under concurrent top-ups, credit notes, the PDF, and the rule that turning GST
 * on raises what renters pay and never lowers what a host earns.
 */
@ControlPlaneTest
class InvoiceAndGstTests {

    /** A GSTIN in Karnataka with a valid check character (a published example). */
    static final String KARNATAKA_GSTIN = "29AAGCB7383J1Z4";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    LegalSettings legal;

    @Autowired
    Ledger ledger;

    @Autowired
    CreditReservationService reservations;

    @Autowired
    IdGenerator ids;

    @Autowired
    MutableClock clock;

    @Autowired
    AuditTrail audit;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    InvoicePdf pdf;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private LegalSettings with(String gstin, LegalSettings.InvoiceMode mode) {
        var base = legal.invoicing();
        return new LegalSettings(
                legal.retention(),
                legal.erasure(),
                legal.notices(),
                legal.support(),
                legal.grievance(),
                legal.credit(),
                legal.topUps(),
                new LegalSettings.Invoicing(
                        "IdleO Technologies Private Limited",
                        "1 Test Road\nBengaluru 560001",
                        gstin,
                        "29",
                        "998315",
                        18,
                        true,
                        mode,
                        false,
                        base.numberPrefix()),
                legal.payouts(),
                legal.tds());
    }

    private Invoices invoices(Gst gst) {
        return new Invoices(jdbc, ids, clock, gst, audit, transactionManager);
    }

    private TestUser renterIn(String state) throws Exception {
        var user = TestUsers.signUp(mvc);
        mvc.perform(put("/v1/wallet/billing-details")
                        .header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stateCode\":\"" + state + "\"}"))
                .andExpect(status().isOk());
        return user;
    }

    /** A paid top-up row, as TopUps leaves one, without going through Razorpay. */
    private TopUps.TopUp topUp(UUID userId, long paise) {
        var id = ids.newId();
        var now = Timestamp.from(clock.instant());
        jdbcTemplate.update(
                """
                insert into billing.topups (id, user_id, amount_paise, currency, receipt, order_id, status, payment_id,
                                            method, credited_by, txn_id, created_at, updated_at, paid_at)
                values (?, ?, ?, 'INR', ?, ?, 'paid', ?, 'upi', 'checkout', ?, ?, ?, ?)
                """,
                id,
                userId,
                paise,
                "tu_" + id.toString().replace("-", ""),
                "order_" + id,
                "pay_" + id,
                UUID.randomUUID(),
                now,
                now,
                now);
        return jdbc.sql("select * from billing.topups where id = :id")
                .param("id", id)
                .query(TopUps::topUp)
                .single();
    }

    @Test
    void aGstinNeedsItsShapeAKnownStateAndItsCheckCharacter() {
        assertThat(Gstin.valid("27AAPFU0939F1ZV")).isTrue();
        assertThat(Gstin.valid(KARNATAKA_GSTIN)).isTrue();
        assertThat(Gstin.valid("29AAGCB7383J1Z5")).as("wrong check character").isFalse();
        assertThat(Gstin.valid("29AAGCB7383J1Y4")).as("the 14th isn't Z").isFalse();
        assertThat(Gstin.valid("99AAGCB7383J1Z4")).as("no such state").isFalse();
        assertThat(Gstin.valid("29aagcb7383j1z4")).isFalse();
        assertThat(Gstin.valid("29AAGCB7383J1Z")).isFalse();
    }

    @Test
    void billingDetailsAreChecked() throws Exception {
        var user = TestUsers.signUp(mvc);
        var details = put("/v1/wallet/billing-details")
                .header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON);

        mvc.perform(details.content("{\"stateCode\":\"98\"}")).andExpect(status().isBadRequest());
        mvc.perform(details.content("{\"stateCode\":\"29\",\"gstin\":\"29AAGCB7383J1Z5\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(details.content("{\"stateCode\":\"27\",\"gstin\":\"" + KARNATAKA_GSTIN + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(details.content("{\"stateCode\":\"29\",\"gstin\":\"" + KARNATAKA_GSTIN.toLowerCase()
                        + "\",\"businessName\":\"Studio\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details.gstin").value(KARNATAKA_GSTIN))
                .andExpect(jsonPath("$.gstPercent").value(0));
        mvc.perform(get("/v1/wallet/billing-details").header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.details.stateCode").value("29"))
                .andExpect(jsonPath("$.states[?(@.code == '29')].name").value("Karnataka"));
    }

    @Test
    void gstIsCgstAndSgstInOurStateAndIgstInAnotherAndRoundsToThePaisa() throws Exception {
        var gst = new Gst(with(KARNATAKA_GSTIN, LegalSettings.InvoiceMode.PER_TOPUP), jdbc);
        var local = renterIn("29");
        var elsewhere = renterIn("27");

        assertThat(gst.taxOn(Paise.of(1_000))).isEqualTo(Paise.of(180));
        assertThat(gst.taxOn(Paise.of(14))).isEqualTo(Paise.of(3)); // 2.52 rounds to 3
        assertThat(gst.renterCost(Paise.of(12))).isEqualTo(Paise.of(14)); // 12 + 2.16
        // ₹500 with GST in it: ₹423.73 before it, ₹76.27 of GST.
        assertThat(gst.taxInside(Paise.of(50_000))).isEqualTo(Paise.of(7_627));
        assertThat(gst.split(local.id(), Paise.of(7_627)))
                .isEqualTo(new Gst.Split(Paise.of(3_813), Paise.of(3_814), Paise.ZERO));
        assertThat(gst.split(elsewhere.id(), Paise.of(7_627)))
                .isEqualTo(new Gst.Split(Paise.ZERO, Paise.ZERO, Paise.of(7_627)));

        var unregistered = new Gst(with("", LegalSettings.InvoiceMode.PER_TOPUP), jdbc);
        assertThat(unregistered.renterCost(Paise.of(1_000))).isEqualTo(Paise.of(1_000));
        assertThat(unregistered.taxInside(Paise.of(50_000))).isEqualTo(Paise.ZERO);
    }

    @Test
    void turningGstOnRaisesWhatTheRenterPaysAndLeavesEveryHostsEarningsAsTheyWere() throws Exception {
        var off = new Gst(with("", LegalSettings.InvoiceMode.MONTHLY_USAGE), jdbc);
        var on = new Gst(with(KARNATAKA_GSTIN, LegalSettings.InvoiceMode.MONTHLY_USAGE), jdbc);
        // An hour at ₹7.20 an hour (12 paise a minute) on each of two hosts.
        long[] earned = new long[2];
        long[] paid = new long[2];
        int i = 0;
        for (var gst : List.of(off, on)) {
            var service = new SessionChargeService(ledger, reservations, 20, legal, clock, gst);
            var renter = renterIn("29");
            var host = UUID.randomUUID();
            var lot = ledger.openLot(
                    renter.id(), CreditLot.Kind.FREE, "credit_grant", UUID.randomUUID(), Paise.of(10_000), null);
            ledger.post(new LedgerTransaction(
                    UUID.randomUUID(),
                    "credit_grant",
                    lot.sourceId(),
                    List.of(
                            LedgerEntry.debit(Account.PROMOTIONS, null, Paise.of(10_000)),
                            LedgerEntry.creditLot(Account.RENTER_WALLET, renter.id(), Paise.of(10_000), lot.id()))));
            var session = UUID.randomUUID();
            var charged = new TransactionTemplate(transactionManager).execute(status -> {
                reservations.reserve(renter.id(), session, Paise.of(1_000));
                var result = service.settle(new SessionCharges.Charge(
                        session,
                        renter.id(),
                        UUID.randomUUID(),
                        List.of(new SessionCharges.Part(host, Paise.of(720))),
                        Paise.ZERO));
                reservations.release(session);
                return result;
            });
            earned[i] = jdbcTemplate.queryForObject(
                    "select coalesce(sum(amount_paise), 0) from billing.ledger_entries where account = 'host_payable'"
                            + " and owner_id = ?",
                    Long.class,
                    host);
            paid[i] = charged.amount().value();
            i++;
        }

        assertThat(earned[1])
                .as("a host earns the same with GST on")
                .isEqualTo(earned[0])
                .isEqualTo(576);
        assertThat(paid[0]).isEqualTo(720);
        assertThat(paid[1]).as("₹7.20 plus 18%").isEqualTo(850);
    }

    @Test
    void perTopUpBoughtCreditCarriesItsGstAsItsSpentAndAnEmptiedLotHasCarriedExactlyIt() throws Exception {
        var gst = new Gst(with(KARNATAKA_GSTIN, LegalSettings.InvoiceMode.PER_TOPUP), jdbc);
        var service = new SessionChargeService(ledger, reservations, 20, legal, clock, gst);
        var renter = renterIn("29");
        // A ₹100 top-up: ₹84.75 before GST, ₹15.25 of GST owed now, against the lot.
        var lot = ledger.openLot(
                renter.id(), CreditLot.Kind.PURCHASED, "topup", UUID.randomUUID(), Paise.of(10_000), null);
        var tax = gst.taxInside(Paise.of(10_000));
        var entries = new ArrayList<LedgerEntry>(List.of(
                LedgerEntry.debit(Account.GATEWAY_CLEARING, null, Paise.of(10_000)),
                LedgerEntry.creditLot(Account.RENTER_PURCHASED, renter.id(), Paise.of(10_000), lot.id()),
                new LedgerEntry(Account.GST_ON_UNUSED_CREDIT, null, tax, Direction.DEBIT, lot.id())));
        entries.addAll(gst.owed(renter.id(), tax, true));
        ledger.post(new LedgerTransaction(UUID.randomUUID(), "topup", lot.sourceId(), entries));
        assertThat(tax).isEqualTo(Paise.of(1_525));

        // Spent in uneven charges until the lot is empty: what each carries out adds up to the lot's GST exactly.
        var host = UUID.randomUUID();
        var left = 10_000L;
        for (long price : new long[] {3_331, 2_222, 1_999}) {
            var session = UUID.randomUUID();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                reservations.reserve(renter.id(), session, gst.renterCost(Paise.of(price)));
                service.settle(new SessionCharges.Charge(
                        session,
                        renter.id(),
                        UUID.randomUUID(),
                        List.of(new SessionCharges.Part(host, Paise.of(price))),
                        Paise.ZERO));
                reservations.release(session);
            });
            left -= gst.renterCost(Paise.of(price)).value();
        }
        var remaining = jdbcTemplate.queryForObject(
                "select remaining_paise from billing.credit_lots where id = ?", Long.class, lot.id());
        assertThat(remaining).isEqualTo(left);
        var outstanding = jdbcTemplate.queryForObject(
                "select coalesce(sum(case direction when 'debit' then amount_paise else -amount_paise end), 0)"
                        + " from billing.ledger_entries where account = 'gst_on_unused_credit' and lot_id = ?",
                Long.class,
                lot.id());
        // What's left in the lot still carries its share of the GST, in proportion.
        assertThat(outstanding).isBetween(1_525L * left / 10_000 - 3, 1_525L * left / 10_000 + 3);
    }

    @Test
    void documentNumbersAreOneGapFreeSequenceAYearUnderConcurrentTopUps() throws Exception {
        var gst = new Gst(legal, jdbc);
        var invoices = invoices(gst);
        var user = renterIn("29");
        var before = jdbcTemplate.queryForList(
                "select sequence from billing.invoices where financial_year = ?",
                Long.class,
                Invoices.financialYear(clock.instant()));
        var topUps = new ArrayList<TopUps.TopUp>();
        for (int n = 0; n < 24; n++) {
            topUps.add(topUp(user.id(), 10_000 + n));
        }
        var numbers = new ArrayList<String>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var calls = new ArrayList<Callable<String>>();
            for (var each : topUps) {
                calls.add(() -> new TransactionTemplate(transactionManager)
                        .execute(status -> invoices.forTopUp(each).number()));
            }
            for (var result : pool.invokeAll(calls)) {
                numbers.add(result.get());
            }
        }
        assertThat(new HashSet<>(numbers)).hasSize(24);
        var sequences = jdbcTemplate.queryForList(
                "select sequence from billing.invoices where financial_year = ? order by sequence",
                Long.class,
                Invoices.financialYear(clock.instant()));
        // Every number from 1 to the last, once: the new ones fill no gap and leave none.
        for (int k = 0; k < sequences.size(); k++) {
            assertThat(sequences.get(k)).isEqualTo(k + 1L);
        }
        assertThat(sequences).hasSize(before.size() + 24);
        // The same top-up again gets its one document back, under the same number.
        var again = new TransactionTemplate(transactionManager).execute(status -> invoices.forTopUp(topUps.getFirst()));
        assertThat(numbers).contains(again.number());
    }

    @Test
    void theFinancialYearRunsAprilToMarchInIndia() {
        assertThat(Invoices.financialYear(Instant.parse("2027-03-31T18:29:59Z")))
                .isEqualTo("2026-27");
        assertThat(Invoices.financialYear(Instant.parse("2027-03-31T18:30:00Z")))
                .isEqualTo("2027-28");
        assertThat(Invoices.financialYear(Instant.parse("2026-10-23T00:00:00Z")))
                .isEqualTo("2026-27");
    }

    @Test
    void unregisteredEveryDocumentSaysItIsNotATaxInvoiceAndChargesNoGst() throws Exception {
        var invoices = invoices(new Gst(with("", LegalSettings.InvoiceMode.PER_TOPUP), jdbc));
        var user = renterIn("29");
        var receipt = new TransactionTemplate(transactionManager)
                .execute(status -> invoices.forTopUp(topUp(user.id(), 50_000)));

        assertThat(receipt.kind()).isEqualTo(Invoices.Kind.RECEIPT);
        assertThat(receipt.document().path("notice").asString()).isEqualTo(Invoices.NOT_A_TAX_INVOICE);
        assertThat(receipt.total()).isEqualTo(Paise.of(50_000));
        assertThat(receipt.cgst().plus(receipt.sgst()).plus(receipt.igst())).isEqualTo(Paise.ZERO);
        assertThat(receipt.number()).matches("IDL\\d{4}-\\d{6}").hasSizeLessThanOrEqualTo(16);
    }

    @Test
    void perTopUpRegisteredATopUpGetsATaxInvoiceWithItsGstInsideItsPrice() throws Exception {
        var invoices = invoices(new Gst(with(KARNATAKA_GSTIN, LegalSettings.InvoiceMode.PER_TOPUP), jdbc));
        var elsewhere = renterIn("27");
        var invoice = new TransactionTemplate(transactionManager)
                .execute(status -> invoices.forTopUp(topUp(elsewhere.id(), 50_000)));

        assertThat(invoice.kind()).isEqualTo(Invoices.Kind.TAX_INVOICE);
        assertThat(invoice.taxable()).isEqualTo(Paise.of(42_373));
        assertThat(invoice.igst()).isEqualTo(Paise.of(7_627));
        assertThat(invoice.total()).isEqualTo(Paise.of(50_000));
        assertThat(invoice.document().path("seller").path("gstin").asString()).isEqualTo(KARNATAKA_GSTIN);
        assertThat(invoice.document().path("placeOfSupply").asString()).isEqualTo("27 Maharashtra");
        assertThat(invoice.document().has("notice")).isFalse();
    }

    @Test
    void monthlyUsageATopUpGetsAReceiptAndTheMonthsCreditUsedATaxInvoice() throws Exception {
        var gst = new Gst(with(KARNATAKA_GSTIN, LegalSettings.InvoiceMode.MONTHLY_USAGE), jdbc);
        var invoices = invoices(gst);
        var service = new SessionChargeService(ledger, reservations, 20, legal, clock, gst);
        var renter = renterIn("29");
        var receipt = new TransactionTemplate(transactionManager)
                .execute(status -> invoices.forTopUp(topUp(renter.id(), 20_000)));
        assertThat(receipt.kind()).isEqualTo(Invoices.Kind.RECEIPT);
        assertThat(receipt.document().has("notice")).isFalse();

        var lot = ledger.openLot(
                renter.id(), CreditLot.Kind.PURCHASED, "topup", receipt.topUpId(), Paise.of(20_000), null);
        ledger.post(new LedgerTransaction(
                UUID.randomUUID(),
                "topup",
                receipt.topUpId(),
                List.of(
                        LedgerEntry.debit(Account.GATEWAY_CLEARING, null, Paise.of(20_000)),
                        LedgerEntry.creditLot(Account.RENTER_PURCHASED, renter.id(), Paise.of(20_000), lot.id()))));
        var session = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            reservations.reserve(renter.id(), session, Paise.of(5_000));
            service.settle(new SessionCharges.Charge(
                    session,
                    renter.id(),
                    UUID.randomUUID(),
                    List.of(new SessionCharges.Part(UUID.randomUUID(), Paise.of(1_000))),
                    Paise.ZERO));
            reservations.release(session);
        });

        var month = YearMonth.from(clock.instant().atZone(Invoices.INDIA));
        invoices.usage(month);
        invoices.usage(month);
        var usage = jdbc.sql("select id from billing.invoices where user_id = :userId and kind = 'usage_invoice'")
                .param("userId", renter.id())
                .query(UUID.class)
                .list();
        assertThat(usage).hasSize(1);
        var invoice = invoices.find(usage.getFirst()).orElseThrow();
        assertThat(invoice.taxable()).isEqualTo(Paise.of(1_000));
        assertThat(invoice.cgst().plus(invoice.sgst())).isEqualTo(Paise.of(180));
        assertThat(invoice.total()).isEqualTo(Paise.of(1_180));
    }

    @Test
    void aCreditNoteTakesTheInvoicesShareOfGstAndNeverMoreThanIsLeft() throws Exception {
        var invoices = invoices(new Gst(with(KARNATAKA_GSTIN, LegalSettings.InvoiceMode.PER_TOPUP), jdbc));
        var renter = renterIn("29");
        var tx = new TransactionTemplate(transactionManager);
        var invoice = tx.execute(status -> invoices.forTopUp(topUp(renter.id(), 50_000)));

        var note = tx.execute(status -> invoices.creditNote(invoice.id(), Paise.of(25_000), "refund of unused credit"));
        assertThat(note.kind()).isEqualTo(Invoices.Kind.CREDIT_NOTE);
        assertThat(note.creditNoteFor()).isEqualTo(invoice.id());
        assertThat(note.total()).isEqualTo(Paise.of(25_000));
        // Half of each tax, rounded down: ₹19.06 of CGST and ₹19.07 of SGST.
        assertThat(note.cgst().plus(note.sgst())).isEqualTo(Paise.of(3_813));
        assertThat(note.document().path("creditNote").path("against").asString())
                .isEqualTo(invoice.number());
        assertThatThrownBy(() -> tx.execute(status -> invoices.creditNote(invoice.id(), Paise.of(25_001), "too much")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRentersDocumentsAreTheirsAsJsonAndPdfAndAnAdminCanCreditOne() throws Exception {
        var renter = renterIn("29");
        var other = renterIn("29");
        var topUp = json.readTree(mvc.perform(post("/v1/wallet/topups")
                        .header("Authorization", renter.bearer())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":30000}"))
                .andReturn()
                .getResponse()
                .getContentAsString());
        var paid = TestRazorpayConfiguration.RAZORPAY.pay(topUp.get("orderId").asString(), "upi");
        mvc.perform(post("/v1/wallet/topups/" + topUp.get("id").asString() + "/confirm")
                        .header("Authorization", renter.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"razorpayPaymentId\":\"%s\",\"razorpaySignature\":\"%s\"}"
                                .formatted(paid.paymentId(), paid.signature())))
                .andExpect(status().isOk());

        var list = json.readTree(mvc.perform(get("/v1/wallet/invoices").header("Authorization", renter.bearer()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        var invoice = list.path("invoices").path(0);
        assertThat(invoice.path("topUpId").asString()).isEqualTo(topUp.get("id").asString());
        assertThat(invoice.path("kind").asString()).isEqualTo("receipt");
        var id = invoice.path("id").asString();
        mvc.perform(get("/v1/wallet/invoices/" + id).header("Authorization", renter.bearer()))
                .andExpect(jsonPath("$.notice").value(Invoices.NOT_A_TAX_INVOICE))
                .andExpect(jsonPath("$.totals.totalPaise").value(30_000))
                .andExpect(jsonPath("$.payment.razorpayPaymentId").value(paid.paymentId()));
        var bytes = mvc.perform(get("/v1/wallet/invoices/" + id + "/pdf").header("Authorization", renter.bearer()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("%PDF-");
        mvc.perform(get("/v1/wallet/invoices/" + id).header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());

        var admin = TestAdmins.signIn(mvc, jdbcTemplate, clock);
        mvc.perform(admin.as(post("/admin/v1/invoices/" + id + "/credit-notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":40000,\"reason\":\"more than the invoice\"}")))
                .andExpect(jsonPath("$.type").value("/problems/action-refused"));
        mvc.perform(admin.as(post("/admin/v1/invoices/" + id + "/credit-notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountPaise\":10000,\"reason\":\"wrong business name on it\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoice.kind").value("credit_note"));
        assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from identity.audit_log where action = 'invoice.credit_note' and target_id = ?",
                        Integer.class,
                        id))
                .isOne();
    }

    @Test
    void theSameDocumentMakesTheSamePdfWithItsFontEmbedded() throws Exception {
        var invoices = invoices(new Gst(with(KARNATAKA_GSTIN, LegalSettings.InvoiceMode.PER_TOPUP), jdbc));
        var renter = renterIn("27");
        var invoice = new TransactionTemplate(transactionManager)
                .execute(status -> invoices.forTopUp(topUp(renter.id(), 12_345)));

        var first = pdf.render(invoice.document());
        var second = pdf.render(invoice.document());
        assertThat(first).isEqualTo(second);
        try (var read = org.apache.pdfbox.Loader.loadPDF(first)) {
            var resources = read.getPage(0).getResources();
            var fonts = new ArrayList<org.apache.pdfbox.pdmodel.font.PDFont>();
            for (var name : resources.getFontNames()) {
                fonts.add(resources.getFont(name));
            }
            assertThat(fonts).isNotEmpty().allSatisfy(font -> {
                assertThat(font.isEmbedded()).isTrue();
                assertThat(font.getName()).containsAnyOf("Inter", "ArchivoDisplay");
            });
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(read))
                    .contains(invoice.number())
                    .contains("₹123.45")
                    .contains("IGST");
        }
    }
}
