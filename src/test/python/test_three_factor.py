import importlib.util
import pathlib
import sys
import unittest

import numpy as np
import pandas as pd
from scipy import stats
from statsmodels.stats.anova import AnovaRM
from statsmodels.stats.multitest import multipletests

sys.dont_write_bytecode = True
PATH = pathlib.Path(__file__).parents[2] / "main/resources/analysis/three_factor.py"
spec = importlib.util.spec_from_file_location("three_factor", PATH)
engine = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine)
MODELS = [{"key": str(i), "name": "Synthetic model " + str(i)} for i in range(7)]


def payload(n=36, human=True):
    rng = np.random.default_rng(8264)
    rows = []
    for i in range(n):
        for j, (context, language, task) in enumerate(engine.CELLS):
            rating = int(rng.integers(1, 11))
            # Independent synthetic correctness with no separated condition.
            correct = rng.random() < (.75 if context == "SAFE" else .45)
            rows.append({"base": str(i), "context": context, "language": language,
                         "task": task, "model": "0", "status": "VALID", "rating": rating,
                         "human_label": engine.LABELS[task][0] if human else None,
                         "model_label": engine.LABELS[task][0 if correct else 1]})
    return {"models": MODELS, "rows": rows}


class AnalysisTest(unittest.TestCase):
    def test_anova_f_and_uncorrected_df_match_statsmodels(self):
        # Library cross-check uses a small balanced panel for speed; the UI
        # independently enforces 30 complete bases before any inferential test.
        source = payload(8)
        bases, y = engine.panel(source["rows"], "ratings")
        frame = pd.DataFrame([{"base": b, "context": c, "language": l, "task": t, "rating": y[i, j]}
                              for i, b in enumerate(bases) for j, (c, l, t) in enumerate(engine.CELLS)])
        expected = AnovaRM(frame, "rating", "base", within=["context", "language", "task"]).fit().anova_table
        for effect, term in zip(engine.rating_effects(y), ["context", "language", "task", "context:language", "context:task", "language:task", "context:language:task"]):
            self.assertAlmostEqual(effect["statistic"], expected.loc[term, "F Value"], places=9)
            self.assertAlmostEqual(effect["df"] / effect["epsilon"], expected.loc[term, "Num DF"], places=9)
            self.assertAlmostEqual(effect["df_error"] / effect["epsilon"], expected.loc[term, "Den DF"], places=9)
            self.assertGreaterEqual(effect["epsilon"], .5)
            self.assertLessEqual(effect["epsilon"], 1)
            self.assertAlmostEqual(effect["p"], stats.f.sf(effect["statistic"], effect["df"], effect["df_error"]), places=12)

    def test_context_two_level_anova_matches_paired_t_squared(self):
        _, y = engine.panel(payload()["rows"], "ratings")
        context = engine.rating_effects(y)[0]
        t = stats.ttest_rel(y[:, :6].mean(axis=1), y[:, 6:].mean(axis=1))
        self.assertAlmostEqual(context["statistic"], t.statistic ** 2, places=10)
        self.assertAlmostEqual(context["p"], t.pvalue, places=10)

    def test_real_engine_produces_both_outcomes_and_joint_wald_tests(self):
        report = engine.analyze(payload())
        self.assertEqual(len(report["tables"]), 2)
        for table in report["tables"]:
            self.assertEqual(len(table["rows"]), 7)
            model = table["rows"][0]
            self.assertEqual(model["complete_bases"], 36)
            self.assertEqual(model["included_prompts"], 432)
            for e in model["effects"]:
                self.assertIsNotNone(e["p"])
                self.assertGreaterEqual(e["adjusted_p"], e["p"])
                self.assertTrue(np.isfinite(e["statistic"]))
        for effect in report["tables"][0]["rows"][0]["effects"]:
            self.assertAlmostEqual(effect["p"], stats.chi2.sf(effect["statistic"], effect["df"]), places=12)
        effects = [e for t in report["tables"] for r in t["rows"] for e in r["effects"]]
        corrected = multipletests([e["p"] if e["p"] is not None else 1 for e in effects], method="holm")[1]
        for e, p in zip(effects, corrected):
            if e["p"] is not None:
                self.assertAlmostEqual(e["adjusted_p"], p, places=12)

    def test_human_labels_are_required_for_correctness_only(self):
        report = engine.analyze(payload(human=False))
        self.assertTrue(all(e["p"] is None for e in report["tables"][0]["rows"][0]["effects"]))
        self.assertTrue(all(e["p"] is not None for e in report["tables"][1]["rows"][0]["effects"]))

    def test_partial_and_failed_panels_are_excluded_without_imputation(self):
        source = payload()
        source["rows"].pop()
        source["rows"][0]["status"] = "HTTP_ERROR"
        report = engine.analyze(source)
        for table in report["tables"]:
            row = table["rows"][0]
            self.assertEqual(row["complete_bases"], 34)
            self.assertEqual(row["included_prompts"], 408)
            self.assertEqual(row["excluded_items"], 23)

    def test_small_samples_separation_and_zero_variance_are_not_reported_as_significant(self):
        small = engine.analyze(payload(2))
        self.assertTrue(all(e["p"] is None for t in small["tables"] for r in t["rows"] for e in r["effects"]))
        source = payload()
        for r in source["rows"]:
            r["model_label"] = r["human_label"]
            r["rating"] = 8
        constant = engine.analyze(source)
        self.assertTrue(all(e["p"] is None for t in constant["tables"] for r in t["rows"] for e in r["effects"]))

    def test_duplicate_cells_and_wrong_model_plan_are_rejected(self):
        source = payload(1)
        source["rows"].append(source["rows"][0].copy())
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            engine.analyze(source)
        with self.assertRaises(ValueError):
            engine.analyze({"models": MODELS[:1], "rows": []})


if __name__ == "__main__":
    unittest.main()
