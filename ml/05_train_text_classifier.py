"""
Step 5 - Train the complaint-text classifiers (category + severity).

Model: TF-IDF (word 1-2 grams + char 3-5 grams would be heavier; we use word
n-grams so the Android side stays tiny) + multinomial Logistic Regression.

The trained model is exported as plain JSON (vocabulary, idf, weights) and
evaluated by TextClassifier.kt on the phone - no ML runtime needed, works
offline and gives the same scores as scikit-learn.

Output: android/app/src/main/assets/text_model.json, output/text_metrics.json

Usage:  python 05_train_text_classifier.py
"""
import json

import numpy as np
import pandas as pd
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, classification_report
from sklearn.model_selection import train_test_split

from config import ANDROID_ASSETS, CATEGORIES, OUT_DIR, SEVERITIES, TEXT_DIR

# These settings are mirrored exactly in TextClassifier.kt
VECTORIZER_ARGS = dict(lowercase=True, token_pattern=r"(?u)\b\w\w+\b",
                       ngram_range=(1, 2), sublinear_tf=True, norm="l2",
                       max_features=4000, min_df=2)


def train_head(X_tr, y_tr, X_te, y_te, classes, name):
    clf = LogisticRegression(max_iter=2000, C=4.0)
    clf.fit(X_tr, y_tr)
    pred = clf.predict(X_te)
    acc = accuracy_score(y_te, pred)
    print(f"\n=== {name}: accuracy {acc:.3f}")
    print(classification_report(y_te, pred, zero_division=0))
    order = [list(clf.classes_).index(c) for c in classes]  # align to our label order
    coef = clf.coef_[order] if clf.coef_.shape[0] > 1 else clf.coef_
    head = {
        "classes": classes,
        "coef": np.round(coef, 5).tolist(),
        "intercept": np.round(clf.intercept_[order], 5).tolist(),
    }
    return head, acc, classification_report(y_te, pred, zero_division=0, output_dict=True)


def main():
    df = pd.read_csv(TEXT_DIR / "civic_complaints.csv").dropna()
    tr, te = train_test_split(df, test_size=0.2, random_state=42, stratify=df["category"])

    vec = TfidfVectorizer(**VECTORIZER_ARGS)
    X_tr = vec.fit_transform(tr["text"])
    X_te = vec.transform(te["text"])

    cat_head, cat_acc, cat_rep = train_head(X_tr, tr["category"], X_te, te["category"], CATEGORIES, "category")
    sev_head, sev_acc, sev_rep = train_head(X_tr, tr["severity"], X_te, te["severity"], SEVERITIES, "severity")

    vocab = {term: int(i) for term, i in vec.vocabulary_.items()}
    model = {
        "format": "civicfix-tfidf-logreg-v1",
        "token_pattern": VECTORIZER_ARGS["token_pattern"],
        "ngram_range": list(VECTORIZER_ARGS["ngram_range"]),
        "sublinear_tf": True,
        "vocabulary": vocab,
        "idf": np.round(vec.idf_, 5).tolist(),
        "category": cat_head,
        "severity": sev_head,
        "metrics": {"category_accuracy": round(cat_acc, 4), "severity_accuracy": round(sev_acc, 4),
                    "train_rows": len(tr), "test_rows": len(te)},
    }
    ANDROID_ASSETS.mkdir(parents=True, exist_ok=True)
    (ANDROID_ASSETS / "text_model.json").write_text(json.dumps(model, separators=(",", ":")), encoding="utf-8")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    (OUT_DIR / "text_metrics.json").write_text(json.dumps(
        {"category": cat_rep, "severity": sev_rep, **model["metrics"]}, indent=2))

    # Self-check: reproduce sklearn's probabilities with the exported numbers,
    # exactly the way the Android code does it.
    sample = te["text"].iloc[0]
    x = vec.transform([sample]).toarray()[0]
    W, b = np.array(cat_head["coef"]), np.array(cat_head["intercept"])
    z = W @ x + b
    p = np.exp(z - z.max()); p /= p.sum()
    print(f"\nSelf-check on: {sample!r} -> {CATEGORIES[int(p.argmax())]} ({p.max():.2f})")
    print(f"Saved {ANDROID_ASSETS / 'text_model.json'}  ({len(vocab)} features)")

    # Generalisation check on hand-written complaints that are NOT produced by
    # the templates in 04_build_text_dataset.py.
    hw_path = TEXT_DIR / "handwritten_eval.csv"
    if hw_path.exists():
        hw = pd.read_csv(hw_path)
        Xh = vec.transform(hw["text"])
        Wc, bc = np.array(cat_head["coef"]), np.array(cat_head["intercept"])
        Ws, bs = np.array(sev_head["coef"]), np.array(sev_head["intercept"])
        pc = [CATEGORIES[i] for i in (Xh @ Wc.T + bc).argmax(1)]
        ps = [SEVERITIES[i] for i in (Xh @ Ws.T + bs).argmax(1)]
        hc = accuracy_score(hw["category"], pc)
        hs = accuracy_score(hw["severity"], ps)
        print(f"\nHand-written eval ({len(hw)} rows): category {hc:.3f}, severity {hs:.3f}")
        for t, c, g in zip(hw["text"], hw["category"], pc):
            if c != g:
                print(f"  miss: {t!r}  expected {c}, got {g}")
        model["metrics"].update(handwritten_category_accuracy=round(hc, 4),
                                handwritten_severity_accuracy=round(hs, 4),
                                handwritten_rows=len(hw))
        (ANDROID_ASSETS / "text_model.json").write_text(json.dumps(model, separators=(",", ":")), encoding="utf-8")
        (OUT_DIR / "text_metrics.json").write_text(json.dumps(
            {"category": cat_rep, "severity": sev_rep, **model["metrics"]}, indent=2))


if __name__ == "__main__":
    main()
