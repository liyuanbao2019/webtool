(function (root, factory) {
    var api = factory();
    if (typeof module === 'object' && module.exports) {
        module.exports = api;
    }
    if (root) {
        root.SqlToolHttp = api;
    }
}(typeof window !== 'undefined' ? window : this, function () {
    function getHeader(headers, name) {
        if (!headers) return '';
        if (typeof headers.get === 'function') {
            return headers.get(name) || '';
        }
        return headers[name.toLowerCase()] || headers[name] || '';
    }

    async function normalizeResponseData(data) {
        if (data && typeof data.text === 'function') {
            try {
                data = await data.text();
            } catch (ignored) {
                return null;
            }
        }
        if (typeof data === 'string') {
            try {
                return JSON.parse(data);
            } catch (ignored) {
                return data;
            }
        }
        return data;
    }

    async function getResponseErrorMessage(error) {
        var response = error && error.response;
        var data = await normalizeResponseData(response && response.data);
        if (data && typeof data === 'object') {
            return data.message || data.errorMessage || '';
        }
        return typeof data === 'string' ? data : '';
    }

    async function isCsrfFailure(error) {
        var response = error && error.response;
        if (!response || response.status !== 403) return false;
        if (getHeader(response.headers, 'x-csrf-error')) return true;
        var message = await getResponseErrorMessage(error);
        return /csrf|token/i.test(message);
    }

    return {
        getResponseErrorMessage: getResponseErrorMessage,
        isCsrfFailure: isCsrfFailure
    };
}));
