package com.liminer.enrich;

import com.liminer.brief.DocumentSectionExtractor;

/*
 * AdvBrochureTestMain — LIVE check of IapdClient.fetchPart2Brochure against
 * adviserinfo.sec.gov, plus the Item 8 extraction that ADVStrategyIndicator
 * performs on the result. Needs network; it is a probe, not an offline unit
 * test, because the thing being verified is that the SEC endpoints answer.
 *
 * Usage: ... com.liminer.enrich.AdvBrochureTestMain [crd ...]
 * Defaults to the two registered advisers in the TestCRMResults2 CRM.
 * Prints ADV_BROCHURE_OK and exits 0 when every CRD yields a Part 2A brochure
 * whose Item 8 section extracts; exits 1 otherwise.
 */
public class AdvBrochureTestMain
{
    public static void main(String[] args0) throws Exception
    {
        String[] crds0 = args0.length > 0 ? args0 : new String[] { "307706", "300844" };
        IapdClient client0 = new IapdClient();
        int failures0 = 0;

        for (String crd0 : crds0)
        {
            System.out.println("=== CRD " + crd0);
            IapdClient.BrochureResult b0 = client0.fetchPart2Brochure(crd0);
            System.out.println("  brochureName : " + b0.brochureName);
            System.out.println("  versionId    : " + b0.versionId);
            System.out.println("  filingDate   : " + b0.filingDate);
            System.out.println("  url          : " + b0.url);
            System.out.println("  text chars   : " + b0.text.length());

            if (b0.text.trim().isEmpty())
            {
                System.err.println("  FAIL: no brochure text for CRD " + crd0);
                failures0++;
                continue;
            }
            if (b0.brochureName.toLowerCase().contains("2b"))
            {
                System.err.println("  FAIL: picked a Part 2B supplement for CRD " + crd0);
                failures0++;
            }

            String item80 = DocumentSectionExtractor.extractSection(b0.text, "Item 8");
            System.out.println("  Item 8 chars : " + item80.length());
            if (item80.trim().isEmpty())
            {
                System.err.println("  FAIL: Item 8 did not extract for CRD " + crd0);
                failures0++;
                continue;
            }
            String head0 = item80.substring(0, Math.min(400, item80.length()))
                .replaceAll("\\s+", " ");
            System.out.println("  Item 8 head  : " + head0);
        }

        if (failures0 > 0)
        {
            System.err.println("AdvBrochureTestMain: " + failures0 + " failure(s)");
            System.exit(1);
        }
        System.out.println("ADV_BROCHURE_OK");
    }
}
