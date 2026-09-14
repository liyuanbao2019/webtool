const { test } = require('node:test');
const assert = require('node:assert/strict');
const http = require('../../main/resources/static/js/sql-tool-http.js');

test('recognizes a CSRF failure carried in a blob download response', async () => {
    const error = {
        response: {
            status: 403,
            headers: {},
            data: new Blob([JSON.stringify({ message: 'CSRF验证失败：Token无效或已过期' })], {
                type: 'application/json'
            })
        }
    };

    assert.equal(await http.isCsrfFailure(error), true);
    assert.match(await http.getResponseErrorMessage(error), /CSRF/);
});

test('uses the explicit CSRF response header without relying on the body type', async () => {
    const error = {
        response: {
            status: 403,
            headers: { 'x-csrf-error': 'invalid-token' },
            data: new Blob([])
        }
    };

    assert.equal(await http.isCsrfFailure(error), true);
});

test('does not retry an unrelated forbidden response', async () => {
    const error = {
        response: {
            status: 403,
            headers: {},
            data: { message: '没有导出权限' }
        }
    };

    assert.equal(await http.isCsrfFailure(error), false);
});
