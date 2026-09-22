/**
 * A minimal `.xlsx` writer — just enough SpreadsheetML for Apache POI to open the
 * workbook and read every cell as text.
 *
 * The cut-over walkthrough has to upload real workbooks: a bad one whose errors
 * the screen must list, a corrected one whose contracts must post to figures
 * derived by hand, and a third one to discard. The repo has no spreadsheet
 * library and adding one for a walkthrough would be a production dependency paid
 * for by a test, so the file is written here.
 *
 * **Every cell is an inline string**, including amounts and dates. That is not a
 * shortcut: `SheetCells.getCellString` renders a numeric cell through
 * `String.valueOf` and a date-formatted one through `LocalDate.toString()`, and
 * the importers parse the resulting text — so a workbook of strings exercises the
 * exact same parsing path as one typed in Excel, without this file having to
 * reproduce Excel's serial-date epoch or POI's shared-string table.
 *
 * **Entries are STORED, not deflated.** `WorkbookGuard.setMinInflateRatio(0.05)`
 * judges an upload by how far it expands; a stored entry expands not at all
 * (ratio 1.0) and so cannot be mistaken for a zip bomb.
 */

const CRC_TABLE: Int32Array = (() => {
    const t = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
        t[n] = c;
    }
    return t;
})();

function crc32(buf: Buffer): number {
    let c = 0xffffffff;
    for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
}

/** A ZIP archive of stored (uncompressed) entries, in the order given. */
function zip(entries: { name: string; data: string }[]): Buffer {
    const chunks: Buffer[] = [];
    const centrals: Buffer[] = [];
    let offset = 0;

    for (const e of entries) {
        const name = Buffer.from(e.name, 'utf8');
        const data = Buffer.from(e.data, 'utf8');
        const crc = crc32(data);

        const local = Buffer.alloc(30 + name.length);
        local.writeUInt32LE(0x04034b50, 0);
        local.writeUInt16LE(20, 4); // version needed to extract
        local.writeUInt16LE(0, 6); // general purpose flags
        local.writeUInt16LE(0, 8); // method 0 = stored
        local.writeUInt16LE(0, 10); // modification time
        local.writeUInt16LE(0x21, 12); // modification date (1980-01-01)
        local.writeUInt32LE(crc, 14);
        local.writeUInt32LE(data.length, 18);
        local.writeUInt32LE(data.length, 22);
        local.writeUInt16LE(name.length, 26);
        local.writeUInt16LE(0, 28);
        name.copy(local, 30);
        chunks.push(local, data);

        const central = Buffer.alloc(46 + name.length);
        central.writeUInt32LE(0x02014b50, 0);
        central.writeUInt16LE(20, 4);
        central.writeUInt16LE(20, 6);
        central.writeUInt16LE(0, 8);
        central.writeUInt16LE(0, 10);
        central.writeUInt16LE(0, 12);
        central.writeUInt16LE(0x21, 14);
        central.writeUInt32LE(crc, 16);
        central.writeUInt32LE(data.length, 20);
        central.writeUInt32LE(data.length, 24);
        central.writeUInt16LE(name.length, 28);
        central.writeUInt16LE(0, 30); // extra
        central.writeUInt16LE(0, 32); // comment
        central.writeUInt16LE(0, 34); // disk number start
        central.writeUInt16LE(0, 36); // internal attributes
        central.writeUInt32LE(0, 38); // external attributes
        central.writeUInt32LE(offset, 42);
        name.copy(central, 46);
        centrals.push(central);

        offset += local.length + data.length;
    }

    const directory = Buffer.concat(centrals);
    const end = Buffer.alloc(22);
    end.writeUInt32LE(0x06054b50, 0);
    end.writeUInt16LE(0, 4);
    end.writeUInt16LE(0, 6);
    end.writeUInt16LE(entries.length, 8);
    end.writeUInt16LE(entries.length, 10);
    end.writeUInt32LE(directory.length, 12);
    end.writeUInt32LE(offset, 16);
    end.writeUInt16LE(0, 20);
    return Buffer.concat([...chunks, directory, end]);
}

const esc = (s: string) =>
    s
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');

/** 0 → A, 25 → Z, 26 → AA. */
function colName(i: number): string {
    let n = i + 1;
    let s = '';
    while (n > 0) {
        const r = (n - 1) % 26;
        s = String.fromCharCode(65 + r) + s;
        n = Math.floor((n - 1) / 26);
    }
    return s;
}

function sheetXml(rows: string[][]): string {
    const body = rows
        .map((cells, r) => {
            const cs = cells
                .map((v, c) =>
                    v === ''
                        ? ''
                        : `<c r="${colName(c)}${r + 1}" t="inlineStr"><is><t xml:space="preserve">${esc(v)}</t></is></c>`,
                )
                .join('');
            return `<row r="${r + 1}">${cs}</row>`;
        })
        .join('');
    return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${body}</sheetData></worksheet>`;
}

export type XlsxSheet = { name: string; rows: string[][] };

/** The workbook as bytes, ready to hand to `setInputFiles` or a multipart POST. */
export function buildXlsx(sheets: XlsxSheet[]): Buffer {
    const parts = sheets.map((s, i) => ({
        ...s,
        file: `xl/worksheets/sheet${i + 1}.xml`,
        rid: `rId${i + 1}`,
    }));
    const styleRid = `rId${parts.length + 1}`;

    const contentTypes = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
${parts
    .map(
        p =>
            `<Override PartName="/${p.file}" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`,
    )
    .join('\n')}
</Types>`;

    const rels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>`;

    const workbook = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets>${parts
        .map((p, i) => `<sheet name="${esc(p.name)}" sheetId="${i + 1}" r:id="${p.rid}"/>`)
        .join('')}</sheets>
</workbook>`;

    const workbookRels = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
${parts
    .map(
        p =>
            `<Relationship Id="${p.rid}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/${p.file
                .split('/')
                .pop()}"/>`,
    )
    .join('\n')}
<Relationship Id="${styleRid}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>`;

    // POI creates its own StylesTable when a workbook has none, but a package
    // that declares one is the shape every real .xlsx has, and it costs 8 lines.
    const styles = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="1"><fill><patternFill patternType="none"/></fill></fills>
<borders count="1"><border/></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs>
</styleSheet>`;

    return zip([
        { name: '[Content_Types].xml', data: contentTypes },
        { name: '_rels/.rels', data: rels },
        { name: 'xl/workbook.xml', data: workbook },
        { name: 'xl/_rels/workbook.xml.rels', data: workbookRels },
        { name: 'xl/styles.xml', data: styles },
        ...parts.map(p => ({ name: p.file, data: sheetXml(p.rows) })),
    ]);
}
