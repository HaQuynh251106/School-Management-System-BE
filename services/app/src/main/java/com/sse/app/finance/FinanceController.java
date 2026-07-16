package com.sse.app.finance;

import com.sse.app.common.ApiException;
import com.sse.app.finance.FinanceDtos.*;
import com.sse.app.identity.UserService;
import com.sse.app.security.CurrentUser;
import com.sse.app.security.CurrentUserHolder;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** A7/D4: Đợt thu, hóa đơn, thanh toán. */
@RestController
public class FinanceController {

    private final FinanceService finance;
    private final UserService users;

    public FinanceController(FinanceService finance, UserService users) {
        this.finance = finance;
        this.users = users;
    }

    // ----- Đợt thu (ADMIN) -----
    @GetMapping("/fee-periods")
    public List<FeePeriod> periods() {
        CurrentUserHolder.requireRole("ADMIN");
        return finance.listPeriods();
    }

    @PostMapping("/fee-periods")
    public FeePeriod createPeriod(@Valid @RequestBody CreateFeePeriodRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        return finance.createPeriod(r);
    }

    @GetMapping("/fee-periods/{id}/items")
    public List<FeePeriodItem> items(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        return finance.itemsOf(id);
    }

    @PostMapping("/fee-periods/{id}/items")
    public FeePeriodItem addItem(@PathVariable String id, @Valid @RequestBody AddFeeItemRequest r) {
        CurrentUserHolder.requireRole("ADMIN");
        return finance.addItem(id, r);
    }

    @PostMapping("/fee-periods/{id}/open")
    public FeePeriod open(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        return finance.open(id);
    }

    @PostMapping("/fee-periods/{id}/generate-invoices")
    public List<Invoice> generate(@PathVariable String id) {
        CurrentUserHolder.requireRole("ADMIN");
        return finance.generateInvoices(id);
    }

    // ----- Hóa đơn -----
    @GetMapping("/invoices")
    public List<Invoice> invoices(@RequestParam(required = false) String studentId,
                                  @RequestParam(required = false) String parentId,
                                  @RequestParam(required = false) String status) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "PARENT", "STUDENT");
        if (me.isParent()) {
            if (studentId != null) { users.assertParentOf(me.id(), studentId); }
            else parentId = me.id();
        } else if (me.isStudent()) {
            studentId = me.id();
        }
        return finance.listInvoices(studentId, parentId, status);
    }

    @GetMapping("/invoices/{id}")
    public Map<String, Object> invoiceDetail(@PathVariable String id) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "PARENT", "STUDENT");
        Invoice inv = finance.getInvoice(id);
        if (me.isParent() && !me.id().equals(inv.getParentId())) {
            users.assertParentOf(me.id(), inv.getStudentId());
        } else if (me.isStudent() && !me.id().equals(inv.getStudentId())) {
            throw ApiException.forbidden("Không phải hóa đơn của bạn");
        }
        return finance.invoiceDetail(id);
    }

    // ----- Thanh toán -----
    @PostMapping("/payments")
    public Map<String, Object> pay(@Valid @RequestBody PayRequest r) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("PARENT", "ADMIN");
        Invoice inv = finance.getInvoice(r.invoiceId());
        if (me.isParent() && !me.id().equals(inv.getParentId())) {
            users.assertParentOf(me.id(), inv.getStudentId());
        }
        return finance.pay(r);
    }

    @PostMapping("/payments/cash")
    public Map<String, Object> recordCash(@Valid @RequestBody PayRequest request) {
        CurrentUserHolder.requireRole("ADMIN");
        return finance.recordCashPayment(request.invoiceId());
    }

    @PostMapping("/payments/callback/{gateway}")
    public Map<String, Object> paymentCallback(@PathVariable String gateway,
                                               @Valid @RequestBody PaymentCallbackRequest request) {
        return finance.completeGatewayPayment(gateway, request);
    }

    @GetMapping("/payments")
    public List<Payment> payments(@RequestParam String invoiceId) {
        CurrentUser me = CurrentUserHolder.require();
        CurrentUserHolder.requireRole("ADMIN", "PARENT", "STUDENT");
        Invoice inv = finance.getInvoice(invoiceId);
        if (me.isParent() && !me.id().equals(inv.getParentId())) {
            users.assertParentOf(me.id(), inv.getStudentId());
        } else if (me.isStudent() && !me.id().equals(inv.getStudentId())) {
            throw ApiException.forbidden("Không phải hóa đơn của bạn");
        }
        return finance.paymentsOf(invoiceId);
    }
}
