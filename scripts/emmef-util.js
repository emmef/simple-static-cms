const DateDisplayType = {
    ISO : "ISO",
    MIXED : "MIXED",
    VAR : "format.dateDisplayType",
};

class DocumentDate {
    static getDateDisplayType() {
        let storedValue = window.localStorage.getItem(DateDisplayType.VAR);
        switch (storedValue) {
            case DateDisplayType.MIXED:
                return DateDisplayType.MIXED;
            default:
                return DateDisplayType.ISO;
        }
    }

    static nextDateDisplayType(displayType) {
        switch (displayType) {
            case DateDisplayType.ISO:
                return DateDisplayType.MIXED;
            default:
                return DateDisplayType.ISO;
        }
    }

    static toggleDateDisplayType() {
        const storedType = DocumentDate.getDateDisplayType();
        const nextType = DocumentDate.nextDateDisplayType(storedType);
        window.localStorage.setItem(DateDisplayType.VAR, nextType);

        return nextType;
    }

    static toggleDateDisplayTypeAndReplace() {
        DocumentDate.toggleDateDisplayType();
        DocumentDate.replaceDates();
    }

    static getDisplayAgeFromMilliSeconds(now, date) {
        let result = date.toISOString();
        if (DocumentDate.getDateDisplayType() === DateDisplayType.ISO) {
            return result.replace(/:[0-9]{2}\.[0-9]{3}Z$/,"");
        }
        let year = date.getFullYear();
        let yearDiff = now.getFullYear() - year;
        let month = date.getMonth();
        let monthDiff = now.getMonth() - month;
        let day = date.getDay();
        let dayDiff = now.getDay() - day;

        if (yearDiff === 0 && monthDiff === 0 && dayDiff === 0) {
            let minutes = Math.round((now.getTime() - date.getTime()) / 60000);
            let hours = Math.floor(minutes / 60);
            minutes -= 60 * hours;
            result = "";
            if (hours > 0) {
                result += hours;
                result += 'h';
            }
            result += minutes;
            result += "m";
            return result;
        }

        result = result.replace(/:[0-9]{2}\.[0-9]{3}Z$/,"");
        if (yearDiff === 0 || (yearDiff === 1 && monthDiff < 0)) {
            result = result.replace(/^[0-9]{4}-/, "");
        }
        if (yearDiff > 0 || monthDiff > 0) {
            result = result.replace(/T[0-9]{2}:[0-9]{2}$/,"");
        }

        return result;
    }



    static getElementDate(element) {
        if (typeof(element.emmefStamp) === "number") {
            return new Date(element.emmefStamp);
        }
        let stampText = element.innerText;
        let stamp = 1 * stampText;
        let stampText2 = "" + stamp;
        let number = stampText !== stampText2 ? null : stamp;
        if (number !== null) {
            element.emmefStamp = stamp;
            return new Date(number);
        }
        return null;
    }

    static replaceDates() {
        let list = document.body.getElementsByClassName("milliseconds-date");
        const now = new Date();
        for (let element of list) {
            let date = DocumentDate.getElementDate(element);
            if (date !== null) {
                element.innerHTML = DocumentDate.getDisplayAgeFromMilliSeconds(now, date);
            }
        }
    }
}

const CONTRASTS = [1, 0.737, 0.543, 0.4, -0.4, -0.543, -0.737, -1.0, ];

class EmmefUtil {
    constructor() {
        this.youtubeFrames = undefined;
        this.articles = undefined;
        this.relativeEmbeddedFrameMargin = 0.1;
    }

    static init() {
        EmmefUtil.resizeEmbeddedFrames();
        for (let element of document.getElementsByClassName("milliseconds-age")) {
            element.addEventListener("click", DocumentDate.toggleDateDisplayTypeAndReplace);
        }
        let replaceDataThread = function() {
            DocumentDate.replaceDates();
            window.setTimeout(replaceDataThread, 10000);
        };
        replaceDataThread();
    }

    static getStoredContrastIdx() {
        let contrastText = window.localStorage.getItem("color.contrast");
        let contrast = 1.0 * contrastText;
        return Math.max(0, Math.min(contrast, CONTRASTS.length - 1));
    }

    static applyStoredContrast() {
        let idx = EmmefUtil.getStoredContrastIdx();
        let contrast = Math.min(Math.max(CONTRASTS[idx], -1), 1);
        window.console.log("Contrast: "+ contrast);
        document.documentElement.style.setProperty("--contrast", "" + contrast);
    }

    static contrast() {
        let idx = EmmefUtil.getStoredContrastIdx();
        let newIdx = idx + 1;
        if (newIdx >= CONTRASTS.length) {
            newIdx = 0;
        }
        window.localStorage.setItem("color.contrast", newIdx.toString());
        EmmefUtil.applyStoredContrast();
    }

    static resizeEmbeddedFrames() {
        let articles = document.body.getElementsByTagName("article");
        let youtubeFrames = document.body.getElementsByClassName("youtube-frame");
        try {
            if (articles && youtubeFrames && articles.length > 0 && youtubeFrames.length > 0) {
                this.articles = articles;
                this.youtubeFrames = youtubeFrames;
                let relativeMargin = getComputedStyle(document.body).getPropertyValue("--embedded-frame-margin");
                if (relativeMargin && !isNaN(parseFloat(relativeMargin))) {
                    this.relativeEmbeddedFrameMargin = Math.min(0.2, Math.max(0.01, parseFloat(relativeMargin)));
                }
                else {
                    this.relativeEmbeddedFrameMargin = 0.1;
                }
                window.console.info(relativeMargin);
                this.resizeYoutubeFrames();
                window.addEventListener("resize", function () {this.resizeYoutubeFrames();});
            }
        }
        catch (e) {
            window.console.error("Could not establish if there are elements to resize", e.toString());
        }
    }

    static resizeYoutubeFrames() {
        if (EmmefUtil.articles && EmmefUtil.youtubeFrames) {
            let articleWidth = 0;
            for (let article of EmmefUtil.articles) {
                articleWidth = Math.max(articleWidth, article.offsetWidth);
            }
            let margin = EmmefUtil.relativeEmbeddedFrameMargin * articleWidth;
            let frameWidth = articleWidth - 2 * margin;
            let frameHeight = 429 * frameWidth / 763;
            for (let frame of EmmefUtil.youtubeFrames) {
                frame.style.width = "" + frameWidth + "px";
                frame.style.height = "" + frameHeight + "px";
                frame.style.marginLeft = "" + margin + "px";
            }
        }
    }
}

EmmefUtil.applyStoredContrast();
