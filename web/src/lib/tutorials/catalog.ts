// src/lib/tutorials/catalog.ts
import type { UserRole } from "../rbac";

/**
 * The Help center's video tutorial catalogue. One entry per web tutorial
 * (tutorials/tutorial-storyboards.md 01–28 and 34–37; 29–33 are mobile-only; 38–46 are the accounting
 * track, .superpowers/accounting-tutorials.md). The Help center lists a topic's entries in array order,
 * so the accounting entries sit in track order (38, the overview, first) rather than id order.
 * An entry with `youtubeId: null` is not published yet and is never shown.
 */
export const TUTORIAL_TOPICS = [
    "getting-started", "portfolio", "leasing", "collections", "accounting", "operations", "tenant-portal",
] as const;
export type TutorialTopic = (typeof TUTORIAL_TOPICS)[number];

export interface Localised { en: string; ar: string }

export interface Tutorial {
    id: string;
    slug: string;
    title: Localised;
    description: Localised;
    topic: TutorialTopic;
    roles: UserRole[];
    durationSec: number;
    youtubeId: string | null;
    relatedArticles?: string[];
}

const ALL: UserRole[] = ["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER", "SECURITY_GUARD", "TENANT_USER", "RENTER", "ACCOUNTANT"];
const ADMINS: UserRole[] = ["SUPER_ADMIN", "TENANT_ADMIN"];
const PORTFOLIO: UserRole[] = ["TENANT_ADMIN", "PROPERTY_MANAGER"];
const FINANCE: UserRole[] = ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT"];

export const TUTORIALS: Tutorial[] = [
    {
        id: "01", slug: "sign-in-and-navigate", topic: "getting-started", roles: ALL, durationSec: 114, youtubeId: "Ieo6R_6q_kE",
        title: { en: "Sign in and find your way around", ar: "تسجيل الدخول والتنقل في النظام" },
        description: {
            en: "Sign in, read the menu for your role, switch between English and Arabic, and manage your profile and password.",
            ar: "سجّل الدخول، وتعرّف على القائمة الخاصة بدورك، وبدّل بين العربية والإنجليزية، وأدِر ملفك الشخصي وكلمة المرور.",
        },
        relatedArticles: ["getting-started--welcome"],
    },
    {
        id: "02", slug: "dashboard-search-notifications-and-help", topic: "getting-started", roles: ALL, durationSec: 121, youtubeId: "Hno2stETeqU",
        title: { en: "Dashboard, search, notifications and help", ar: "لوحة التحكم والبحث والإشعارات والمساعدة" },
        description: {
            en: "Read the dashboard, find records with global search, work through notifications and use the Help center and guided tours.",
            ar: "اقرأ لوحة التحكم، وابحث عن السجلات بالبحث الشامل، وتابع الإشعارات، واستخدم مركز المساعدة والجولات الإرشادية.",
        },
    },
    {
        id: "03", slug: "roles-permissions-and-organisation-switching", topic: "getting-started", roles: ADMINS, durationSec: 125, youtubeId: "0LwTc07Vpn8",
        title: { en: "Roles, permissions and switching organisation", ar: "الأدوار والصلاحيات والتبديل بين المؤسسات" },
        description: {
            en: "See what each role can do, from Company Admin to Tenant and security guard, and switch between organisations safely.",
            ar: "تعرّف على ما يستطيع كل دور القيام به، من مدير المؤسسة إلى المستأجر وحارس الأمن، وبدّل بين المؤسسات بأمان.",
        },
        relatedArticles: ["getting-started--roles-and-permissions"],
    },
    {
        id: "04", slug: "provision-organisations-and-feature-access", topic: "getting-started", roles: ["SUPER_ADMIN"], durationSec: 118, youtubeId: "OfZjCTNnvww",
        title: { en: "Set up organisations and feature access", ar: "إعداد المؤسسات وإتاحة الميزات" },
        description: {
            en: "Create and edit an organisation, then turn features such as listings, meetings and gate passes on or off.",
            ar: "أنشئ مؤسسة وعدّل بياناتها، ثم فعّل أو أوقف ميزات مثل الإعلانات والاجتماعات وتصاريح الدخول.",
        },
        relatedArticles: ["admin--super-admin-guide"],
    },
    {
        id: "05", slug: "create-users-and-staff", topic: "getting-started", roles: ADMINS, durationSec: 120, youtubeId: "h0oUPeedez4",
        title: { en: "Create users and staff", ar: "إنشاء المستخدمين والموظفين" },
        description: {
            en: "Add users with the right role, assign them to properties and keep staff records active or inactive.",
            ar: "أضف المستخدمين بالدور المناسب، وخصّصهم للعقارات، وحافظ على سجلات الموظفين نشطة أو غير نشطة.",
        },
        relatedArticles: ["admin--managing-staff"],
    },
    {
        id: "06", slug: "create-a-project-and-property-portfolio", topic: "portfolio", roles: PORTFOLIO, durationSec: 117, youtubeId: "6Fc437Wa_1g",
        title: { en: "Create a project and property portfolio", ar: "إنشاء مشروع ومحفظة عقارية" },
        description: {
            en: "Set up projects and properties with bilingual names, emirate, address and Makani number, and review them as cards or a table.",
            ar: "أنشئ المشاريع والعقارات بأسماء عربية وإنجليزية، مع الإمارة والعنوان ورقم مكاني، واعرضها كبطاقات أو جدول.",
        },
        relatedArticles: ["properties--managing-properties", "getting-started--first-property-setup"],
    },
    {
        id: "07", slug: "buildings-units-contacts-amenities-and-parking", topic: "portfolio", roles: PORTFOLIO, durationSec: 127, youtubeId: "Bi9IFBiKd48",
        title: { en: "Buildings, units, contacts, amenities and parking", ar: "المباني والوحدات وجهات الاتصال والمرافق والمواقف" },
        description: {
            en: "Add buildings and floors, define units and their rents, and record property contacts, amenities and parking spots.",
            ar: "أضف المباني والطوابق، وحدّد الوحدات وإيجاراتها، وسجّل جهات اتصال العقار ومرافقه ومواقف السيارات.",
        },
        relatedArticles: ["properties--units-and-buildings"],
    },
    {
        id: "08", slug: "import-a-property-portfolio-in-bulk", topic: "portfolio", roles: ["TENANT_ADMIN"], durationSec: 100, youtubeId: "RY9eEIEY1bM",
        title: { en: "Import a property portfolio in bulk", ar: "استيراد المحفظة العقارية دفعة واحدة" },
        description: {
            en: "Download the template, upload your portfolio, fix rows that fail validation and check what was created.",
            ar: "نزّل النموذج، وارفع محفظتك العقارية، وصحّح الصفوف التي لم تجتز التحقق، وراجع ما تم إنشاؤه.",
        },
    },
    {
        id: "09", slug: "manage-tenants-and-portal-access", topic: "leasing", roles: PORTFOLIO, durationSec: 104, youtubeId: "aNcKWqSNRF8",
        title: { en: "Manage Tenants and portal access", ar: "إدارة المستأجرين والوصول إلى البوابة" },
        description: {
            en: "Create Tenant profiles with bilingual names, contacts and preferred language, and give a Tenant a portal account.",
            ar: "أنشئ ملفات المستأجرين بالأسماء العربية والإنجليزية وبيانات الاتصال واللغة المفضلة، وامنح المستأجر حسابًا في البوابة.",
        },
    },
    {
        id: "10", slug: "draft-a-tenancy-contract-and-generate-its-cheques", topic: "leasing", roles: PORTFOLIO, durationSec: 127, youtubeId: "Wag-eszCzqM",
        title: { en: "Draft a tenancy contract and generate its cheques", ar: "صياغة عقد إيجار وإنشاء شيكاته" },
        description: {
            en: "Choose the Tenant and unit, set dates, rent, VAT and charges, and generate the cheque grid before saving the draft.",
            ar: "اختر المستأجر والوحدة، وحدّد التواريخ والإيجار وضريبة القيمة المضافة والرسوم، وأنشئ جدول الشيكات قبل حفظ المسودة.",
        },
        relatedArticles: ["leases--creating-a-lease", "leases--payment-schedules"],
    },
    {
        id: "11", slug: "generate-review-and-sign-a-tenancy-contract", topic: "leasing", roles: [...PORTFOLIO, "RENTER"], durationSec: 72, youtubeId: "85sO-iLpEHA",
        title: { en: "Generate, review and sign a tenancy contract", ar: "إنشاء عقد الإيجار ومراجعته وتوقيعه" },
        description: {
            en: "Generate the contract PDF, let the Tenant review it, regenerate after a rejection and record the acceptance.",
            ar: "أنشئ ملف العقد بصيغة PDF، ودع المستأجر يراجعه، وأعد إنشاءه بعد الرفض، وسجّل القبول.",
        },
    },
    {
        id: "12", slug: "administer-an-active-tenancy-contract", topic: "leasing", roles: PORTFOLIO, durationSec: 97, youtubeId: "rLwltCxGZBQ",
        title: { en: "Administer an active tenancy contract", ar: "إدارة عقد إيجار نشط" },
        description: {
            en: "Add a charge with its Ejari number and cheque, attach supporting documents and log follow-ups on an active contract.",
            ar: "أضف رسومًا إلى عقد نشط برقم إيجاري وشيكها، وأرفق المستندات الداعمة، وسجّل المتابعات.",
        },
        relatedArticles: ["leases--lease-lifecycle"],
    },
    {
        id: "13", slug: "renew-extend-terminate-and-settle", topic: "leasing", roles: ["SUPER_ADMIN", ...PORTFOLIO, "RENTER"], durationSec: 133, youtubeId: "nzKOEO8MjOs",
        title: { en: "Renew, extend, terminate and settle a contract", ar: "تجديد العقد وتمديده وإنهاؤه وتسويته" },
        description: {
            en: "Renew a contract for a new term, extend one by a month, take a Tenant's notice, terminate the contract and finalise the settlement.",
            ar: "جدّد العقد لمدة جديدة، ومدّد عقدًا لشهر إضافي، وسجّل إشعار المستأجر، وأنهِ العقد، وأكمل التسوية النهائية.",
        },
        relatedArticles: ["leases--lease-lifecycle"],
    },
    {
        id: "14", slug: "penalties-and-cheque-failure-fines", topic: "collections", roles: [...PORTFOLIO, "ACCOUNTANT", "RENTER"], durationSec: 108, youtubeId: "SJRZi5tivc0",
        title: { en: "Penalties, fines and recharges", ar: "الغرامات وإعادة تحميل تكاليف الصيانة" },
        description: {
            en: "Configure fines, approve or waive proposed penalties and maintenance recharges, and see the journal each decision writes.",
            ar: "اضبط إعدادات الغرامات، واعتمد الغرامات المقترحة وتكاليف الصيانة المعاد تحميلها أو أعفِ منها، وتعرّف على القيد الذي يُنشئه كل قرار.",
        },
    },
    {
        id: "15", slug: "manage-rent-cheques-end-to-end", topic: "collections", roles: PORTFOLIO, durationSec: 150, youtubeId: null,
        title: { en: "Manage rent cheques end to end", ar: "إدارة شيكات الإيجار من البداية إلى النهاية" },
        description: {
            en: "Collect, deposit, clear or return cheques, add notes, and upload cheque images in bulk.",
            ar: "استلم الشيكات وأودعها وصرّفها أو سجّل ارتجاعها، وأضف الملاحظات، وارفع صور الشيكات دفعة واحدة.",
        },
        relatedArticles: ["leases--payment-schedules"],
    },
    {
        id: "16", slug: "configure-online-rent-collection", topic: "collections", roles: ADMINS, durationSec: 150, youtubeId: null,
        title: { en: "Configure online rent collection", ar: "إعداد تحصيل الإيجار عبر الإنترنت" },
        description: {
            en: "Choose a payment provider, set rent collection settings and check that online payments are ready.",
            ar: "اختر مزوّد الدفع، واضبط إعدادات تحصيل الإيجار، وتحقّق من جاهزية الدفع عبر الإنترنت.",
        },
        relatedArticles: ["admin--tenant-settings"],
    },
    {
        id: "38", slug: "how-accounting-works-in-miftah", topic: "accounting", roles: FINANCE, durationSec: 148, youtubeId: "WKMbpGSgrdU",
        title: { en: "How accounting works in Miftah", ar: "كيف تعمل المحاسبة في مفتاح" },
        description: {
            en: "Follow one tenancy contract's money from posting to the reports, and see where each step shows in the tenant ledger, general ledger, trial balance and profit and loss.",
            ar: "تتبّع أموال عقد إيجار واحد من الترحيل إلى التقارير، وتعرّف على موضع كل خطوة في دفتر أستاذ المستأجر ودفتر الأستاذ العام وميزان المراجعة وقائمة الأرباح والخسائر.",
        },
    },
    {
        id: "39", slug: "start-your-books-opening-balances-and-cut-over", topic: "accounting", roles: ["TENANT_ADMIN", "ACCOUNTANT"], durationSec: 141, youtubeId: "jGsnA2bUlOQ",
        title: { en: "Start your books: opening balances and cut-over", ar: "بدء الدفاتر: الأرصدة الافتتاحية والانتقال إلى النظام" },
        description: {
            en: "Set the date Miftah takes over your books, enter opening balances, bring running contracts across and reconcile against your old system.",
            ar: "حدّد تاريخ بدء الدفاتر في مفتاح، وأدخل الأرصدة الافتتاحية، وانقل العقود السارية، وطابق النتائج مع نظامك السابق.",
        },
    },
    {
        id: "17", slug: "chart-of-accounts-template-and-charge-types", topic: "accounting", roles: FINANCE, durationSec: 121, youtubeId: "seIQjEg9AQE",
        title: { en: "Chart of accounts, property account sets and charge types", ar: "دليل الحسابات ومجموعات حسابات العقارات وأنواع الرسوم" },
        description: {
            en: "See how the chart of accounts is organised, how every property gets its own set of accounts, and which account each charge on a contract posts to.",
            ar: "تعرّف على تنظيم دليل الحسابات، وكيف يحصل كل عقار على مجموعة حساباته الخاصة، وإلى أي حساب يُرحَّل كل بند من بنود العقد.",
        },
        relatedArticles: ["finance--chart-of-accounts"],
    },
    {
        id: "34", slug: "post-a-tenancy-contract", topic: "accounting", roles: ["TENANT_ADMIN", "ACCOUNTANT"], durationSec: 102, youtubeId: "IOlWHhomeBU",
        title: { en: "Post a tenancy contract", ar: "ترحيل عقد الإيجار" },
        description: {
            en: "Post a draft contract with its lines and cheque grid, and see the contract become active with its recognition schedule planned.",
            ar: "رحّل مسودة العقد مع بنودها وجدول شيكاتها، وشاهد العقد يصبح نشطًا مع جدولة الاعتراف بالإيراد.",
        },
    },
    {
        id: "35", slug: "register-and-clear-cheques", topic: "collections", roles: ["TENANT_ADMIN", "PROPERTY_MANAGER", "ACCOUNTANT"], durationSec: 96, youtubeId: null,
        title: { en: "Register, bank and clear cheques", ar: "تسجيل الشيكات وإيداعها وتحصيلها" },
        description: {
            en: "Bank a batch of cheques, clear them when the bank confirms, and see the journal each step writes.",
            ar: "أودع مجموعة من الشيكات في البنك، وسجّل تحصيلها عند تأكيد البنك، وتعرّف على القيد الذي تُنشئه كل خطوة.",
        },
    },
    {
        id: "40", slug: "returned-cheques-replace-settle-write-off-and-recover", topic: "accounting", roles: ["TENANT_ADMIN", "ACCOUNTANT"], durationSec: 150, youtubeId: null,
        title: { en: "Returned cheques: replace, settle in cash, write off and recover", ar: "الشيكات المرتجعة: الاستبدال والتسوية النقدية والشطب والتحصيل" },
        description: {
            en: "Handle a cheque the bank returns: replace it, take cash instead, or write the debt off and record what you later recover.",
            ar: "تعامل مع الشيك الذي يرتجعه البنك: استبدله، أو اقبل مبلغه نقدًا، أو اشطب الدين كدين معدوم وسجّل ما تحصّله لاحقًا.",
        },
    },
    {
        id: "36", slug: "month-end-recognition", topic: "accounting", roles: ["TENANT_ADMIN", "ACCOUNTANT"], durationSec: 128, youtubeId: null,
        title: { en: "Month-end recognition", ar: "الاعتراف بالإيراد في نهاية الشهر" },
        description: {
            en: "Run recognition to a cut-off date and watch advance rent move into rental income, period by period.",
            ar: "شغّل الاعتراف بالإيراد حتى تاريخ الإقفال، وشاهد انتقال الإيجار المقدم إلى إيراد الإيجار فترة بعد فترة.",
        },
    },
    {
        id: "41", slug: "vat-per-instalment-and-the-vat-return", topic: "accounting", roles: ["TENANT_ADMIN", "ACCOUNTANT"], durationSec: 150, youtubeId: null,
        title: { en: "VAT per instalment and the VAT return", ar: "ضريبة القيمة المضافة لكل دفعة والإقرار الضريبي" },
        description: {
            en: "See how 5% VAT on a commercial contract waits as not yet due until each instalment's date, then file the quarter's VAT return.",
            ar: "تعرّف على كيفية بقاء ضريبة القيمة المضافة بنسبة 5% على العقد التجاري غير مستحقة حتى تاريخ كل دفعة، ثم قدّم الإقرار الضريبي للربع.",
        },
    },
    {
        id: "42", slug: "termination-settlement-and-deposit-refunds-in-the-books", topic: "accounting", roles: ["TENANT_ADMIN", "ACCOUNTANT"], durationSec: 130, youtubeId: "g5T8WEPwJvU",
        title: { en: "Termination, settlement and deposit refunds in the books", ar: "إنهاء العقد والتسوية واسترداد التأمين في الدفاتر" },
        description: {
            en: "Terminate a contract early, read the unearned-rent reversal, finalise the settlement and pay the deposit refund.",
            ar: "أنهِ العقد مبكرًا، واقرأ قيد عكس الإيجار غير المكتسب، واعتمد التسوية النهائية، وادفع مبلغ التأمين المسترد.",
        },
    },
    {
        id: "43", slug: "renewals-rent-free-periods-and-unit-transfers-in-the-books", topic: "accounting", roles: ["TENANT_ADMIN", "ACCOUNTANT"], durationSec: 126, youtubeId: "ewNV-NBy8zI",
        title: { en: "Renewals, rent-free periods and unit transfers in the books", ar: "التجديد وفترات الإعفاء من الإيجار ونقل الوحدات في الدفاتر" },
        description: {
            en: "Renew a contract with an increase and a renewal fee, give a rent-free fit-out month, and move a Tenant to another unit without losing a dirham.",
            ar: "جدّد العقد بزيادة في الإيجار ورسوم تجديد، وامنح شهرًا معفى من الإيجار لفترة التجهيز، وانقل المستأجر إلى وحدة أخرى دون أن يضيع درهم واحد.",
        },
    },
    {
        id: "44", slug: "supplier-bills-payment-runs-and-issued-cheques", topic: "accounting", roles: ["TENANT_ADMIN", "ACCOUNTANT"], durationSec: 130, youtubeId: "EFaGtSVA-KM",
        title: { en: "Supplier bills, payment runs and issued cheques", ar: "فواتير الموردين ودفعات السداد والشيكات الصادرة" },
        description: {
            en: "Post supplier bills with input VAT, pay what is due in a payment run, and follow a post-dated supplier cheque until the bank pays it.",
            ar: "رحّل فواتير الموردين مع ضريبة المدخلات، وسدّد المستحق من خلال دفعة سداد، وتابع الشيك المؤجل الصادر للمورد حتى يصرفه البنك.",
        },
    },
    {
        id: "19", slug: "vendors-and-bank-accounts", topic: "accounting", roles: FINANCE, durationSec: 150, youtubeId: null,
        title: { en: "Vendors and bank accounts", ar: "الموردون والحسابات البنكية" },
        description: {
            en: "Create and maintain vendors and bank accounts and link them to the right properties.",
            ar: "أنشئ الموردين والحسابات البنكية وحافظ عليها، واربطها بالعقارات المناسبة.",
        },
    },
    {
        id: "45", slug: "bank-reconciliation", topic: "accounting", roles: ["TENANT_ADMIN", "ACCOUNTANT"], durationSec: 150, youtubeId: null,
        title: { en: "Bank reconciliation", ar: "التسوية البنكية" },
        description: {
            en: "Import a bank statement, match it to the books, book the bank-only lines and finalise the month.",
            ar: "استورد كشف الحساب البنكي، وطابقه مع الدفاتر، وسجّل البنود الواردة في كشف البنك فقط، واعتمد تسوية الشهر.",
        },
    },
    {
        id: "18", slug: "journal-vouchers-create-read-and-reverse", topic: "accounting", roles: FINANCE, durationSec: 150, youtubeId: null,
        title: { en: "Journal vouchers: create, read and reverse", ar: "سندات القيد: الإنشاء والقراءة والعكس" },
        description: {
            en: "Post a balanced journal voucher, read its lines and correct a mistake by reversing it.",
            ar: "رحّل سند قيد متوازنًا، واقرأ بنوده، وصحّح الخطأ بعكس القيد.",
        },
    },
    {
        id: "37", slug: "tenant-ledger", topic: "accounting", roles: ["TENANT_ADMIN", "ACCOUNTANT"], durationSec: 118, youtubeId: null,
        title: { en: "Tenant ledger, control account and trial balance", ar: "دفتر أستاذ المستأجر والحساب الرقابي وميزان المراجعة" },
        description: {
            en: "Read a Tenant's ledger, tie it to the Rent Receivable control account in the general ledger, and check the trial balance.",
            ar: "اقرأ دفتر أستاذ المستأجر، وطابقه مع حساب الإيجار المستحق الرقابي في دفتر الأستاذ العام، وتحقّق من ميزان المراجعة.",
        },
    },
    {
        id: "20", slug: "dashboards-and-financial-reports", topic: "accounting", roles: FINANCE, durationSec: 150, youtubeId: null,
        title: { en: "Financial reports: profit and loss, balance sheet, owner statement and aging", ar: "التقارير المالية: الأرباح والخسائر والميزانية العمومية وكشف حساب المالك وأعمار الذمم" },
        description: {
            en: "Read the Profit & Loss, the Property Profit Report by building, the Balance Sheet, the Owner Statement and receivable and payable aging.",
            ar: "اقرأ قائمة الأرباح والخسائر وتقرير أرباح العقار لكل مبنى والميزانية العمومية وكشف حساب المالك وأعمار الذمم المدينة والدائنة.",
        },
    },
    {
        id: "46", slug: "period-lock-and-year-end-close", topic: "accounting", roles: ["TENANT_ADMIN", "ACCOUNTANT"], durationSec: 150, youtubeId: null,
        title: { en: "Period lock and year-end close", ar: "إقفال الفترات وإقفال نهاية السنة" },
        description: {
            en: "Lock closed months, close the fiscal year into retained earnings, and re-open it if you must.",
            ar: "أقفل الأشهر المنتهية، وأقفل السنة المالية بترحيل نتيجتها إلى الأرباح المحتجزة، وأعد فتحها عند الحاجة.",
        },
    },
    {
        id: "21", slug: "tenant-payments", topic: "tenant-portal", roles: ["RENTER"], durationSec: 150, youtubeId: null,
        title: { en: "Tenant payments", ar: "مدفوعات المستأجر" },
        description: {
            en: "As a Tenant, see your payment schedule, pay online and download receipts from your payment history.",
            ar: "بصفتك مستأجرًا، اطّلع على جدول دفعاتك، وادفع عبر الإنترنت، ونزّل الإيصالات من سجل المدفوعات.",
        },
        relatedArticles: ["renter--making-payments", "renter--renter-portal-overview"],
    },
    {
        id: "22", slug: "submit-and-manage-maintenance-tickets", topic: "operations", roles: [...PORTFOLIO, "TENANT_USER", "RENTER"], durationSec: 150, youtubeId: null,
        title: { en: "Submit and manage maintenance tickets", ar: "تقديم طلبات الصيانة وإدارتها" },
        description: {
            en: "Raise a ticket, assign and track it, confirm closure with a one-time code and review ticket reports.",
            ar: "قدّم طلب صيانة، وعيّنه وتابع حالته، وأكّد إغلاقه برمز لمرة واحدة، وراجع تقارير الطلبات.",
        },
        relatedArticles: ["renter--submitting-tickets"],
    },
    {
        id: "23", slug: "schedule-and-manage-meetings", topic: "operations", roles: [...PORTFOLIO, "RENTER"], durationSec: 150, youtubeId: null,
        title: { en: "Schedule and manage meetings", ar: "جدولة الاجتماعات وإدارتها" },
        description: {
            en: "Request a meeting about a property or unit, approve it, follow it on the calendar and mark it complete.",
            ar: "اطلب اجتماعًا بشأن عقار أو وحدة، ووافق عليه، وتابعه في التقويم، وسجّل اكتماله.",
        },
    },
    {
        id: "24", slug: "facilities-and-booking-approvals", topic: "operations", roles: [...PORTFOLIO, "RENTER"], durationSec: 150, youtubeId: null,
        title: { en: "Facilities and booking approvals", ar: "المرافق والموافقة على الحجوزات" },
        description: {
            en: "Browse facilities and availability, request a booking or parking spot, and approve requests as a manager.",
            ar: "تصفّح المرافق ومواعيد توفرها، واطلب حجزًا أو موقف سيارة، ووافق على الطلبات بصفتك مديرًا.",
        },
    },
    {
        id: "25", slug: "gate-passes-and-walk-in-visitors", topic: "operations", roles: [...PORTFOLIO, "RENTER", "SECURITY_GUARD"], durationSec: 150, youtubeId: null,
        title: { en: "Gate passes and walk-in visitors", ar: "تصاريح الدخول والزوار المباشرون" },
        description: {
            en: "Issue and approve gate passes, admit walk-in visitors, set the access policy and review entry reports.",
            ar: "أصدر تصاريح الدخول ووافق عليها، واسمح بدخول الزوار المباشرين، واضبط سياسة الدخول، وراجع تقارير الدخول.",
        },
    },
    {
        id: "26", slug: "publish-and-manage-property-listings", topic: "portfolio", roles: PORTFOLIO, durationSec: 150, youtubeId: null,
        title: { en: "Publish and manage property listings", ar: "نشر إعلانات العقارات وإدارتها" },
        description: {
            en: "Create a listing with details and photos, publish or withdraw it, and follow up on enquiries.",
            ar: "أنشئ إعلانًا بالتفاصيل والصور، وانشره أو اسحبه، وتابع الاستفسارات الواردة.",
        },
    },
    {
        id: "27", slug: "browse-the-marketplace-and-wishlist", topic: "tenant-portal", roles: ["RENTER"], durationSec: 150, youtubeId: null,
        title: { en: "Browse the marketplace and keep a wishlist", ar: "تصفّح السوق وقائمة الأمنيات" },
        description: {
            en: "Filter available listings, open the details, save favourites to your wishlist and send an enquiry.",
            ar: "صفِّ الإعلانات المتاحة، وافتح تفاصيلها، واحفظ المفضلة في قائمة الأمنيات، وأرسل استفسارًا.",
        },
    },
    {
        id: "28", slug: "promotions-offers-and-coupons", topic: "operations", roles: ["TENANT_ADMIN", "RENTER"], durationSec: 150, youtubeId: null,
        title: { en: "Promotions, offers and coupons", ar: "العروض الترويجية والخصومات والقسائم" },
        description: {
            en: "Create a promotion, target it, add images and copy, and see how Tenants find offers and use coupons.",
            ar: "أنشئ عرضًا ترويجيًا، وحدّد جمهوره، وأضف الصور والنصوص، وتعرّف على كيفية وصول المستأجرين إلى العروض واستخدام القسائم.",
        },
    },
];

/** YouTube video ids are 11 URL-safe characters; anything else is refused before it reaches a URL. */
const YOUTUBE_ID = /^[A-Za-z0-9_-]{11}$/;

export type PublishedTutorial = Tutorial & { youtubeId: string };

export function publishedTutorials(list: Tutorial[]): PublishedTutorial[] {
    return list.filter((t): t is PublishedTutorial => t.youtubeId !== null && YOUTUBE_ID.test(t.youtubeId));
}

export function filterTutorialsByRole<T extends Tutorial>(list: T[], role?: UserRole): T[] {
    if (!role) return [];
    return list.filter((t) => t.roles.includes(role));
}

export function searchTutorials<T extends Tutorial>(list: T[], query: string): T[] {
    const q = query.toLowerCase().trim();
    if (!q) return list;
    return list.filter((t) =>
        [t.title.en, t.title.ar, t.description.en, t.description.ar].some((s) => s.toLowerCase().includes(q)));
}

export function tutorialsForArticle(slug: string, list: Tutorial[] = TUTORIALS): PublishedTutorial[] {
    return publishedTutorials(list).filter((t) => t.relatedArticles?.includes(slug));
}

export const embedUrl = (youtubeId: string) =>
    `https://www.youtube-nocookie.com/embed/${encodeURIComponent(youtubeId)}?rel=0`;

export const thumbnailUrl = (youtubeId: string) =>
    `https://i.ytimg.com/vi/${encodeURIComponent(youtubeId)}/hqdefault.jpg`;

export function formatDuration(sec: number): string {
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m}:${String(s).padStart(2, "0")}`;
}
