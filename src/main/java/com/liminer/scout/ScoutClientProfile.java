package com.liminer.scout;

import com.liminer.core.UserAccount;

import java.util.ArrayList;
import java.util.List;

/*
 * ScoutClientProfile — thin POJO describing the fundraising client for the
 * Investor Scout pipeline: how big a check they seek and where, used by
 * ScoutPrefilter's resources-band and geography gates. sectorTags is carried
 * through unused here for the later fit-scoring stage.
 */
public class ScoutClientProfile
{
    public static final double DEFAULT_CHECK_SIZE_FRACTION = 0.075;

    public double targetFundSize;
    public double checkSize;
    public String geography;
    public List<String> sectorTags;

    public ScoutClientProfile()
    {
        targetFundSize = 0.0;
        checkSize = 0.0;
        geography = "";
        sectorTags = new ArrayList<String>();
    }

    /*
     * Effective check size: explicit checkSize if set (> 0), otherwise 7.5%
     * of targetFundSize.
     */
    public double effectiveCheckSize()
    {
        if (checkSize > 0.0)
        {
            return checkSize;
        }

        return targetFundSize * DEFAULT_CHECK_SIZE_FRACTION;
    }

    public static ScoutClientProfile fromUserAccount(UserAccount user0)
    {
        ScoutClientProfile profile0 = new ScoutClientProfile();
        if (user0 == null)
        {
            return profile0;
        }

        profile0.geography = user0.clientGeography == null ? "" : user0.clientGeography;
        profile0.sectorTags = splitPipeOrComma(user0.clientSectorTags);

        return profile0;
    }

    private static List<String> splitPipeOrComma(String value0)
    {
        List<String> result0 = new ArrayList<String>();
        if (value0 == null || value0.trim().length() == 0)
        {
            return result0;
        }

        String[] pieces0 = value0.split("[|,]");
        for (String piece0 : pieces0)
        {
            String trimmed0 = piece0.trim();
            if (trimmed0.length() > 0)
            {
                result0.add(trimmed0);
            }
        }

        return result0;
    }
}
