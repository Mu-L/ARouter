package com.alibaba.android.arouter.kspfixture.legacy;

import android.content.Context;
import com.alibaba.android.arouter.facade.annotation.Route;

@Route(path = "/legacy/provider")
public final class LegacyProvider implements LegacyService {
    private boolean initialized;

    @Override
    public void init(Context context) {
        initialized = true;
    }

    @Override
    public String backend() {
        if (!initialized) {
            throw new IllegalStateException("KAPT provider was not initialized");
        }
        return "kapt";
    }
}
