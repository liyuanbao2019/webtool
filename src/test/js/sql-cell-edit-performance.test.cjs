const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const template = fs.readFileSync(
    path.join(__dirname, '../../main/resources/templates/sql-tool.html'),
    'utf8'
);

test('cell editor input does not use root-component reactive v-model', () => {
    assert.doesNotMatch(template, /v-model="editingCell\.value"/);
    assert.match(template, /editingCell:\s*Vue\.markRaw\(\{ active: false \}\)/);
    assert.match(template, /document\.createElement\('input'\)/);
});

test('large result table is memoized independently from transient editor and selection state', () => {
    const memo = template.match(/<table ref="resultTable"[\s\S]*?v-memo="([^"]+)"/);
    assert.ok(memo, 'result table must use v-memo');
    assert.doesNotMatch(memo[1], /editingCell|resultCellSelection|selectedCellCount/);
    assert.match(template, /resultCellSelection:\s*Vue\.markRaw/);
});
