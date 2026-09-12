package com.alibaba.android.arouter.kspfixture.legacy;

import androidx.fragment.app.Fragment;
import com.alibaba.android.arouter.facade.annotation.Autowired;

/** Compiled with KAPT before the KSP module; its helper must remain discoverable. */
public class LegacyInjectedFragment extends Fragment {
    @Autowired public int inherited = 17;
}
