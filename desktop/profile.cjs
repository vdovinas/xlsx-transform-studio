const operators = new Set(['EQUALS', 'NOT_EQUALS', 'CONTAINS', 'NOT_CONTAINS', 'STARTS_WITH', 'EMPTY', 'NOT_EMPTY', 'GREATER', 'LESS', 'DATE_BEFORE', 'DATE_AFTER']);
const actions = new Set(['TRIM', 'LOWER', 'UPPER', 'REPLACE', 'EXTRACT', 'CONSTANT', 'IF_EMPTY', 'IF_EMPTY_SOURCE', 'CLASSIFY', 'NUMBER', 'DATE']);

function validateProfile(profile) {
  const fail = () => { throw new Error('Некорректный профиль. Откройте JSON-профиль, сохранённый в XLSX Studio.'); };
  const object = value => value && typeof value === 'object' && !Array.isArray(value);
  const text = value => typeof value === 'string';
  const conditions = list => Array.isArray(list) && list.every(c => object(c) && text(c.source) && text(c.value) && operators.has(c.operator) && (c.ignoreCase === undefined || typeof c.ignoreCase === 'boolean'));
  const group = value => object(value) && (value.matchAll === undefined || typeof value.matchAll === 'boolean') && conditions(value.conditions || []);
  if (!object(profile) || !text(profile.name) || !text(profile.sheetName) || !Number.isInteger(profile.headerRow) || profile.headerRow < 1 || profile.headerRow > 1048576) fail();
  if (profile.csv !== undefined && (!object(profile.csv) || !['auto', 'UTF-8', 'windows-1251'].includes(profile.csv.encoding) || !['auto', ';', ',', '\t'].includes(profile.csv.delimiter))) fail();
  if (!conditions(profile.filters || []) || !Array.isArray(profile.filterGroups || []) || !(profile.filterGroups || []).every(group)) fail();
  if (profile.matchAll !== undefined && typeof profile.matchAll !== 'boolean') fail();
  if (!Array.isArray(profile.fields) || profile.fields.length > 16384) fail();
  for (const field of profile.fields) {
    if (!object(field) || !text(field.name) || !text(field.source) || !['TEXT', 'NUMBER', 'DATE'].includes(field.kind)) fail();
    if ((field.format !== undefined && !text(field.format)) || (field.required !== undefined && typeof field.required !== 'boolean')) fail();
    if (!Array.isArray(field.steps || [])) fail();
    for (const step of field.steps || []) {
      if (!group(step) || !actions.has(step.action) || !text(step.argument ?? '') || !text(step.replacement ?? '') || !Array.isArray(step.cases || [])) fail();
      if (!(step.cases || []).every(branch => group(branch) && text(branch.value))) fail();
    }
  }
  if (JSON.stringify(profile).length > 5_000_000) fail();
  return profile;
}
module.exports = { validateProfile };
