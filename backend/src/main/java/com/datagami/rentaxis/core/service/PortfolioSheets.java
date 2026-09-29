package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.util.List;

/**
 * Break-it R4 ops4 F1: the portfolio workbook's tenant and contract sheets.
 *
 * <p>The product calls them Tenants and Tenancy Contracts (the Import Portfolio
 * dialog says so, and so does the template); the importer was written when they
 * were Renters and Leases, and workbooks built from the old template still use
 * those names. Both are accepted — the product's name first.</p>
 */
public final class PortfolioSheets {

    public static final String TENANTS = "Tenants";
    public static final String TENANTS_OLD = "Renters";
    public static final String CONTRACTS = "Tenancy Contracts";
    public static final String CONTRACTS_OLD = "Leases";

    private PortfolioSheets() {
    }

    /** The Tenants sheet, or the older Renters one; null when neither exists. */
    public static Sheet tenants(Workbook wb) {
        Sheet s = wb.getSheet(TENANTS);
        return s != null ? s : wb.getSheet(TENANTS_OLD);
    }

    /** The Tenancy Contracts sheet, or the older Leases one; null when neither exists. */
    public static Sheet contracts(Workbook wb) {
        Sheet s = wb.getSheet(CONTRACTS);
        return s != null ? s : wb.getSheet(CONTRACTS_OLD);
    }

    static String missing(String name, String oldName) {
        return "Sheet '" + name + "' is missing (the older name '" + oldName + "' is accepted too)";
    }

    /**
     * The validators label their rows with the old sheet names; a workbook that
     * uses the new ones is told about the sheet it has.
     */
    static void relabel(List<ImportErrorDTO> rows, Sheet tenants, Sheet contracts) {
        for (ImportErrorDTO e : rows) {
            if (tenants != null && TENANTS_OLD.equals(e.getSheet())) e.setSheet(tenants.getSheetName());
            else if (contracts != null && CONTRACTS_OLD.equals(e.getSheet())) e.setSheet(contracts.getSheetName());
        }
    }
}
